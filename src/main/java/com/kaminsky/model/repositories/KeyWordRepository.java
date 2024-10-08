package com.kaminsky.model.repositories;

import com.kaminsky.model.KeyWord;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KeyWordRepository extends CrudRepository<KeyWord, Long> {
}
