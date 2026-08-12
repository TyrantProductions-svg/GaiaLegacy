package com.gaia.shell;

import com.gaia.save.format.SaveGameId;
import com.gaia.session.LoadWorldRequest;
import com.gaia.session.NewWorldRequest;
import java.util.Objects;

/** Closed product-lifecycle work requested by one routed shell command. */
public sealed interface ProductLifecycleIntent permits
        ProductLifecycleIntent.None,
        ProductLifecycleIntent.StartNewWorld,
        ProductLifecycleIntent.LoadWorld,
        ProductLifecycleIntent.Save,
        ProductLifecycleIntent.DeleteWorld,
        ProductLifecycleIntent.RecoverBackup,
        ProductLifecycleIntent.CloseActiveSession,
        ProductLifecycleIntent.ExitProduct {

    record None() implements ProductLifecycleIntent {}

    record StartNewWorld(NewWorldRequest request) implements ProductLifecycleIntent {
        public StartNewWorld {
            Objects.requireNonNull(request, "request");
        }
    }

    record LoadWorld(LoadWorldRequest request) implements ProductLifecycleIntent {
        public LoadWorld {
            Objects.requireNonNull(request, "request");
        }
    }

    record Save(SavePolicy policy) implements ProductLifecycleIntent {
        public Save {
            Objects.requireNonNull(policy, "policy");
        }
    }

    record DeleteWorld(SaveGameId saveGameId) implements ProductLifecycleIntent {
        public DeleteWorld {
            Objects.requireNonNull(saveGameId, "saveGameId");
        }
    }

    record RecoverBackup(SaveGameId saveGameId) implements ProductLifecycleIntent {
        public RecoverBackup {
            Objects.requireNonNull(saveGameId, "saveGameId");
        }
    }

    record CloseActiveSession() implements ProductLifecycleIntent {}

    record ExitProduct() implements ProductLifecycleIntent {}

    enum SavePolicy {
        SAVE_AND_STAY,
        SAVE_AND_QUIT
    }

    static ProductLifecycleIntent none() {
        return new None();
    }
}
