# 1. 问题来源
售电服务`sell-electricity-v1`生产环境在2019年6月12日18:20至22:00和7月4日16:30至19:00两个时间段均出现了频繁Full GC，其中6月12日的监控图形如下：

![图1](/uploads/899d900d6ad27d273fed005eedf18779/图1.png)

![图2](/uploads/185a6f33471c1f3d59afdd5911cae195/图2.png)

7月4日的监控图形与之类似，它们共同的特点是：

* GC频率达到800到1000ops以上
* 因GC而造成了暂停时间持续超过2秒
* Full GC完全无法释放堆内存，老年代使用率持续维持在峰顶水平长达数个小时，但始终没有OOM
* 期间有一些http的用户请求，后经排查这些请求都是些简单请求，不会操作大量数据

最终：

* 6月12日的异常情况在21:50左右的一次Full GC后自动恢复正常，期间没有人工干预，因此也没有及时留下dump文件
* 7月4日的异常情况在17:00人工介入排查并留下了dump文件，19:00重启服务后恢复正常

# 2. 初步排查
6月12日第一次出现该异常情况时，综合对监控数据以及期间用户请求的分析，发现有两处较为反常：

* 用户请求的压力非常低，但内存使用率特别高，尤其是老年代的使用率
* 一般来说，Full GC都清理不掉的内存，时间一长就会OOM；但实际情况下，异常持续了3个半小时后自动恢复正常了

基于此，当时的结论是：

* 售电服务存在后台线程在偷跑
* 该后台线程在操作总量为几百MB内存的对象

但由于没有留下dump文件，没有任何线索和证据支持进一步的排查，因此问题暂时遗留

# 3. 堆栈分析
7月4日第二次出现该异常情况时，由于人工快速介入，留下了dump文件，因此得以进一步排查问题

使用的工具是Eclipse Memory Analyzer Tool（mat）。将dump文件导入mat，生成`Dominator Tree`和`Leak Suspects`两个分析页面

## 3.1. 堆
`Dominator Tree`显示了异常发生时堆内存中各种对象的内存使用率，如下图所示：

![图3](/uploads/6535b8f111a9f3514f78f0ff1ed5097f/图3.png)

单个`TokenBuffer`对象就占用了近600MB的内存，占用率达到堆内存总量的**84%**。`TokenBuffer`引用了它的内部类`Segment`的一个链表，`Segment`主要由两部分组成：

1. 它所引用的下一个`Segment`对象
2. 一个`Object`数组，该数组中存在大量`SerializedString`对象

通过对`com.fasterxml.jackson.databind.util.TokenBuffer`类的源码分析发现，它实际上是json工具`jackson`的`ObjectMapper`类中，用于将java对象序列化成json字符串的中间对象：`ObjectMapper`会先将java对象的成员变量序列化成字符串并存放入`Segment`的`Object`数组中，再将`Segment`的链表放入`TokenBuffer`中；其中，成员变量的名称通常被序列化成`SerializedString`对象（作为对比，字符串类型的变量值通常被序列化为`String`对象）

因此，上述`Object`数组存放的`SerializedString`对象极有可能就是被序列化的java对象的成员变量名。通过对上图中`TokenBuffer`对象的进一步排查，发现下面一组字符串在`SerializedString`对象的值中频繁出现：

    composites
    lazy
    foreignColumn
    constructorResultMappings
    propertyResultMappings
    notNullColumns
    flags
    ID
    mappedColumns
    mappedProperties
    ...

可以很明显的看出，这些成员变量的名称与`MyBatis`有很大关系。因此，可以初步推测：MyBatis的某个或某些对象在某个死循环中被jackson反复序列化，导致内存泄漏

## 3.2. 栈
`Leak Suspects`显示了内存泄漏发生时线程的栈轨迹（StackTrace），它提供了3个非常关键的信息

### 3.2.1. 循环递归调用
如下图：

![图4](/uploads/31eba83361063fe34c6ef60fed01e7cb/图4.png)

类似的循环递归调用在栈轨迹中打印了将近10000行，它证实了在此过程中jackson确实在不停地做序列化，且序列化的对象之间存在嵌套关系。栈轨迹的行数表明该过程极有可能已经陷入了死循环，但还没有栈溢出（StackOverflow）

### 3.2.2. ConfigurationPropertiesReportEndpoint
如上图，在jackson循环不断地进行序列化的过程中，除了jackson自己的类以外，`org.springframework.boot.actuate.endpoint.ConfigurationPropertiesReportEndpoint`也在反复参与

`ConfigurationPropertiesReportEndpoint`类是`spring-boot-starter-actuator`所引入的`/configprops`的实现类，它会扫描所有带有`@ConfigurationProperties`注解的Spring Bean，并将它们序列化成json字符串后输出

因此，可以推测：jackson反复序列化导致内存泄漏的问题，是由`/configprops`触发的

### 3.2.3. 触发点
如下图所示是栈轨迹的最底部，即线程的入口附近：

![图5](/uploads/2046d02c36f55bf530e0744b77ad2d01/图5.png)

可以看出：

* jackson的`ObjectMapper`是由`ConfigurationPropertiesReportEndpoint`触发的
* `ConfigurationPropertiesReportEndpoint`是由Spring的JMX触发的
* Spring的JMX是由Prometheus的JMX触发的

# 4. 尝试复现
由堆栈分析可知，异常的关键节点在于JMX和`ConfigurationPropertiesReportEndpoint`，且与MyBatis相关。但是售电服务的代码中并没有与JMX相关的代码，对MyBatis相关的调用进行系统性排查后也没有发现明显存在内存泄漏的地方，因此只能尝试通过`ConfigurationPropertiesReportEndpoint`复现问题，即调用`/configprops`

## 4.1. 调试/configprops

在本地环境调试（debug）售电服务（确保代码和二方包是与生产环境一致），调用`/configprops`，并在结果中检索`mybatis`，得到如下结果：

![图6](/uploads/c85c1b5cf6c0bf4613c48fc4868b1b40/图6.png)

`tk.mybatis.mapper.autoconfigure.MyBatisProperties`报错，错误信息是：`Cannot serialize 'mybatis'`

尝试在`ConfigurationPropertiesReportEndpoint`中检索该错误信息，发现它位于`safeSerialize`方法中：

![图7](/uploads/ee4121fc765f78b34c5f7b3c0d9578f3/图7.png)

如上图中对catch中的语句打上断点，再次调用`/configprops`时捕获到异常，打印异常信息如下：

![图8](/uploads/e277884d4b2eb1fda3e2c4d68f310a27/图8.png)

## 4.2. 调试XNode

`org.apache.ibatis.parsing.XNode`是MyBatis中将xml文件解析成树结构的树节点对象。上述异常信息表明：`ConfigurationPropertiesReportEndpoint`在将`MyBatisProperties`转换成json字符串时，涉及到了对mapper xml文件的解析，解析的过程中出现了父节点调用子节点、子节点又调用父节点的循环递归调用；该循环递归调用直接导致线程栈溢出（Stackoverflow），致使json序列化失败

进一步地，在`XNode`类的`getParent`方法中打上断点，如下图：

![图9](/uploads/4063d888c587ac4c657f09d8ed362419/图9.png)

再次调用后发现，当解析扫描到1次`<sql>`标签后，后续的解析就在同一个xml文件的`<mapper>`标签与`<resultMap>`（即`<mapper>`下的第1个标签，对应了异常信息中的`XNode["children"]->ArrayList[0]`）标签之间不断循环，如下图：

![图10](/uploads/7d3df9bb9ed752e046cd3da46711c393/图10.png)

进一步调试观察上下文环境发现，每扫描1次`XNode`，jackson的`ObjectMapper`都会根据`XNode`对象的数据生成一个新的`Segment`对象，并存放入`TokenBuffer`中。因而，随着循环递归的进行，`Segment`对象将会越来越多，造成**内存泄漏**

另一方面，在售电服务的项目代码中，去掉所有mybatis mapper xml文件中的`<sql>`标签，再进行调试。结果是tk.mybatis对应的`MyBatisProperties`不再报错且能被正确解析为json字符串，如下图；前述的两个断点也不再被拦截

![图11](/uploads/a345db87ed9fc8f95cc34440c2809b94/图11.png)

## 4.3. 小结
MyBatis的mapper xml文件中如果存在`<sql>`标签，会导致`ConfigurationPropertiesReportEndpoint`在解析时出现父节点调用子节点、子节点又调用父节点的循环递归调用，该调用会生成大量包含了字符串的`Segment`对象，造成内存泄漏

但此处仍有两个疑问：

1. tk.mybatis报错，但原生的org.mybatis未报错，因此是否为tk.mybatis引入的问题？
2. **更重要的是**，由于复现现象（StackOverflow）与生产环境（频繁Full GC但未报错）不一致，因此二者是否为同一个问题？

# 5. 异常模拟
## 5.1. MyBatis
为了判断是否为tk.mybatis引入的问题，新启一个试验项目进行验证。该项目具备如下特征：

* 依赖MyBatis，但不依赖tk.mybatis
* spring-boot版本号1.5.10，与售电服务生产环境一致
* 依赖spring-boot-starter-actuator
* mapper xml文件中存在`<sql>`标签

调用`/configprops`，结果如下：

![图12](/uploads/badf4beaeeadec8c44441b5fe0f0fa09/图12.png)

可见，org.mybatis对应的`MyBatisProperties`同样报错：`Cannot serialize 'mybatis'`

再对前述`ConfigurationPropertiesReportEndpoint`和`XNode`的断点处打上断点，然后调用`/configprops`，拦截到的信息与异常情况一致

因此，该异常与tk.mybatis无关，是MyBatis的固有问题

## 5.2. StackOverflow的栈轨迹
`ConfigurationPropertiesReportEndpoint.safeSerialize`方法在处理异常的时候，直接return而未打印异常栈轨迹

尝试在return前添加打印栈轨迹的代码，再次调用`/configprops`，打印的栈轨迹截取如下图：

![图13](/uploads/1a33981dfb36028c46631d0aa0f04564/图13.png)

可见，`StackOverflowError`下的栈轨迹与堆栈分析中的栈轨迹基本是一致的。由此可以推断，复现现象和生产环境的异常实际上是同一个问题

## 5.3. 小结
综上可以推断：售电服务生产环境长时间频繁Full GC的异常问题，是由于调用`/configprops`后，在将`MyBatisProperties`转换成json字符串的过程中，由于循环递归而导致**内存泄漏**

正常情况下，异常线程会在短时间内栈溢出，从而通过GC释放掉泄漏的堆内存

但是在生产环境频繁Full GC的异常场景下，线程迟迟没有栈溢出，导致堆内存即使是在频繁Full GC的情况下，仍然始终无法释放。在6月12日第一次异常发生的时候，系统最终自动恢复了正常，原因应该就是在22:50左右异常线程终于发生了栈溢出，因而被泄漏的堆内存在一瞬间被释放

# 6. JMX
以上分析找出了异常的底层原因，但上层调用者仍然没有找到，原因是无论是售电服务的用户请求还是Prometheus对JVM监控（二者都基于HTTP），都不会直接调用`/configprops`

查阅Spring Boot Actuator相关的文档发现，所有的endpoint（包括`configprops`）都会暴露两套接口，一套基于HTTP，另一套则基于JMX。而堆栈分析中的异常栈轨迹也表明，Prometheus的JMX模块调用了Spring的JMX模块，而Spring的JMX模块调用了`ConfigurationPropertiesReportEndpoint`，即`configprops`

通过排查最终在售电服务的启动命令中发现：

![图14](/uploads/eff936fe74d9d2684fef50d9a5110830/图14.png)

售电服务通过javaagent参数的形式从外部依赖了`jmx_prometheus_javaagent`插件，通过它来进行kafka的监控，并向Prometheus暴露指标

查阅`jmx_prometheus_javaagent`的[资料](https://github.com/prometheus/jmx_exporter)，发现如下两个配置：

![图15](/uploads/74e13c0afd76916e206101e642d33555/图15.png)

所以，默认情况下，`jmx_prometheus_javaagent`将查询目标java程序中的所有MBean，即对于Spring Boot Actuator，它将遍历调用所有的endpoint，包括`configprops`

# 7. 问题总结
触发条件：

* 启动参数中引入了`jmx_prometheus_javaagent`插件
* `jmx_prometheus_javaagent`的配置中未排除`configprops`这个endpoint
* Spring Boot Actuator中未禁用`configprops`
* mybatis mapper xml文件中存在`<sql>`标签

调用过程：

* `jmx_prometheus_javaagent`查询`configprops`
* `configprops`的实现类`ConfigurationPropertiesReportEndpoint`开始将`MyBatisProperties`序列化成json字符串
* json工具jackson的`ObjectMapper`在扫描mybatis mapper xml文件时，遇到`<sql>`标签，从而进入循环递归调用
* 循环递归调用的过程中，jackson生成了包含大量`Segment`对象的链表，造成内存泄漏

异常结果：

* 大多数情况下，线程短时间内就会栈溢出，泄漏的堆内存被GC释放
* 特殊情况下，线程长时间没有栈溢出，堆中被泄漏的内存逐渐增多，造成频繁Full GC却释放不掉内存的情况；最终的结果：
    * 栈比堆先溢出，则堆中被泄漏的内存迅速被GC释放，程序自动恢复正常
    * 堆比栈先溢出，则程序因为OOM而崩溃

# 8. 解决方案
1. 在`jmx_prometheus_javaagent`配置的黑名单中添加`configprops`
2. 在Spring Boot Actuator中禁用`configprops`

# 9. 关联问题
## 9.1. 现象
在排查的过程中，曾经尝试在**用电服务**的开发环境调用`/configprops`，结果：

* 本次调用直接卡死，无返回
* 调用后，其它涉及数据库查询的正常用户请求也全部卡死，无返回

排查中发现：

* 用电服务的CPU使用率和内存使用率都**没有**明显异常
* 通过netstat命令查看Oracle数据库的连接，发现连接被打满；多次重试（重启用电服务后调用`/configprops`）后发现，一旦用电服务调用`/configprops`，数据库连接就会被立即打满

也就是说，对用电服务调用`/configprops`，数据库连接会被瞬间打满

## 9.2. 分析
### 9.2.1. 数据库连接数
结合**售电服务**内存泄漏问题的排查结果分析，调用`/configprops`出现卡死的问题，极大概率是添加了`@ConfigurationProperties`注解的Spring Bean在被序列化成json字符串时发生了循环递归依赖；再加上数据库连接出现问题，则极有可能是`DataSource`的问题

排查相关的代码发现，在最近的git commit中，`chinawyny-starter-web`项目的`MapperConfig`类添加了如下代码：

![图16](/uploads/24a0101fbc745ca8113f3fdfa9e3b008/图16.png)

既然与数据库连接相关，那么在`DruidDataSource`类的`getConnection()`方法中打上断点，再调用`/configprops`，会发现：该断点被重复拦截到，且每次拦截到时，`DruidDataSource`中的`ConnectCount`均**+1**，如下图；当`ConnectCount`超过一定数量后（开发环境是10），用电服务就会卡死

![图17](/uploads/07d78e0f3d1ec95b86ec4124636feb9a/图17.png)

深入阅读`DruidDataSource.getConnection()`的源码发现，其每次被调用时都会从数据库连接池中获取一个jdbc连接；如果池中的连接都被占用，则会尝试新建一个。这就解释了数据库连接数为什么一直上涨

### 9.2.2. 循环递归引用
在阅读`DruidDataSource`的源码中，另一个发现则是存在循环递归引用，如下图：

![图18](/uploads/58f94375bda2060c97a51dfa3b1f0bcc/图18.png)

## 9.3. 结论
用电服务调用`/configprops`时：

* 尝试将`MapperConfig`类中添加了`@ConfigurationProperties`注解的`DruidDataSource`序列化成json字符串
* 序列化的过程中，遇到`DruidDataSource`与`DruidConnectionHolder`的循环递归引用
* 每次序列化递归引用的`DruidDataSource`的对象时，都会调用`DruidDataSource.getConnection()`方法，该方法会尝试新建一个数据库jdbc连接，导致连接数持续上涨至最大后，程序卡死

另外，售电服务在调用`/configprops`不会出现因为数据库连接数暴涨而卡死的原因是：

* 售电服务发布时，`MapperConfig`类中添加`DruidDataSource`的代码还未提交
* 此时，`spring.datasource`配置项对应的类不是`DruidDataSource`，而是`org.springframework.boot.autoconfigure.jdbc.DataSourceProperties`（如下图），该类中不存在数据库连接相关的对象

![图19](/uploads/cafe54d8cf1da8957745f899ef2eceab/图19.png)