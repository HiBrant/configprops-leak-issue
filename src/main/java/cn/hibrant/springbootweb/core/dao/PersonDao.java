package cn.hibrant.springbootweb.core.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import cn.hibrant.springbootweb.core.entity.Person;

@Repository
public interface PersonDao {

	List<Person> getAll();
}
