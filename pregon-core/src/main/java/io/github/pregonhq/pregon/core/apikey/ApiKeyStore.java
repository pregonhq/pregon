package io.github.pregonhq.pregon.core.apikey;

import java.util.Optional;

/** Port to wherever API keys are stored. */
public interface ApiKeyStore {

    Optional<StoredApiKey> findById(String id);

    /** Records that a key was just used; implementations may coalesce frequent calls. */
    void recordUse(String id);
}
