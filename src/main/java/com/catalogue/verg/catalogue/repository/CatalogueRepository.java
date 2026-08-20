package com.catalogue.verg.catalogue.repository;

import com.catalogue.verg.catalogue.entity.CatalogueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogueRepository extends JpaRepository<CatalogueEntity, String> {

}