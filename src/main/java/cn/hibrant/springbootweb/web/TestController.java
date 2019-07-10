package cn.hibrant.springbootweb.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.hibrant.springbootweb.core.dao.PersonDao;

@RestController
public class TestController {
	
	@Autowired
	private PersonDao personDao;

	@GetMapping("/hello")
	public String hello() throws ClassNotFoundException {
		return "hello world";
	}
	
	@GetMapping("/persons")
	public Object getAllPersons() {
		return personDao.getAll();
	}
}
