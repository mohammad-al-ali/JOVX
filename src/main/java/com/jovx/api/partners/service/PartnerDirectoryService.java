package com.jovx.api.partners.service;

import com.jovx.api.partners.model.PartnerModels.PublicStore;
import com.jovx.api.partners.repository.PartnerRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-postgres")
public class PartnerDirectoryService {
  private final PartnerRepository repository;

  public PartnerDirectoryService(PartnerRepository repository) {
    this.repository = repository;
  }

  /** Active stores of active partners; the discount shown is the partner-wide minimum. */
  public List<PublicStore> publicDirectory(java.util.UUID city) {
    return repository.publicDirectory(city);
  }
}
