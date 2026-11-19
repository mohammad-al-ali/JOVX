package com.jovx.api.partners.controller;

import com.jovx.api.partners.model.PartnerModels.PublicEstablishment;
import com.jovx.api.partners.service.PartnerDirectoryService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Public guest browsing (plan decision 44): no accounts, phones of logins or internal ids of owners. */
@RestController
@Profile("local-postgres")
public class PublicPartnerController {
  private final PartnerDirectoryService directory;

  public PublicPartnerController(PartnerDirectoryService directory) {
    this.directory = directory;
  }

  @GetMapping("/api/v1/public/partners")
  public List<PublicEstablishment> partners() {
    return directory.publicDirectory();
  }
}
