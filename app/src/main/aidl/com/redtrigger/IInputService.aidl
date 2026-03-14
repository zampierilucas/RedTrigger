package com.redtrigger;

import com.redtrigger.ITriggerCallback;

interface IInputService {
    /** Detect SAR input device paths. Returns comma-separated list. */
    String detectDevices();

    /** Start reading events and calling back. */
    void startReading(ITriggerCallback callback);

    /** Stop reading. */
    void stopReading();

    /** Enable/disable key injection (remap F7/F8 to gamepad buttons). */
    void setInjectionEnabled(boolean enabled);

    /** Grant a runtime permission to the app (runs as shell uid). */
    void grantPermission(String permission);

    /** Destroy the service. */
    void destroy();
}
