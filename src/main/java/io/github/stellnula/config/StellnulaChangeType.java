package io.github.stellnula.config;

import io.github.stellnula.auth.*;
import io.github.stellnula.client.*;
import io.github.stellnula.grpc.*;
import io.github.stellnula.internal.*;
import io.github.stellnula.management.*;
import io.github.stellnula.store.*;
import io.github.stellnula.telemetry.*;
import io.github.stellnula.transport.*;

public enum StellnulaChangeType {
    UPSERT,
    DELETE,
    GRAY_CHANGED
}
