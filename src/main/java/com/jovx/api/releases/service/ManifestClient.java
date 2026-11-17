package com.jovx.api.releases.service;

import com.jovx.api.releases.model.ReleaseModels.*;
import java.util.Optional;

/** Reads the CI-published version.json for one app. */
public interface ManifestClient {
  /** Empty when no manifest source is configured for this environment. */
  Optional<ReleaseManifest> latest(AppKind app, Platform platform);
}
