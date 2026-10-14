package com.gradingplatform.backend.sandbox;

/** How one sandbox run ended (4.1a). The first five are the program's doing, the last the sandbox's. */
public enum SandboxStatus {
    /** The program ran to the end and exited with 0. Whether its output is right is decided elsewhere. */
    OK,
    /** The program was not built (compiled languages only); the compiler's output is in the result. */
    COMPILE_ERROR,
    /** The program exited with a non-zero code or was killed by a signal of its own making. */
    RUNTIME_ERROR,
    /** The wall-clock limit was reached and the program was stopped. */
    TIMEOUT,
    /** The kernel killed the program for using more memory than its limit (the container's out-of-memory flag, 4.5a). */
    MEMORY_LIMIT,
    /** The sandbox failed (Docker unavailable, image missing...): says nothing about the program. */
    SANDBOX_ERROR
}
