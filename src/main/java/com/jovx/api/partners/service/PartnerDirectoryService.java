package com.jovx.api.partners.service;

import com.jovx.api.partners.model.PartnerModels.PublicEstablishment;
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

  /** Discount shown is the partner-wide minimum; the public contact phone is optional. */
  public List<PublicEstablishment> publicDirectory() {
    return repository.publicDirectory();
  }
}
