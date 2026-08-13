package com.kpit.vspmanager.poll;

import com.kpit.vspmanager.model.DumpResult;
import com.kpit.vspmanager.model.PropertySnapshot;

import java.util.List;

/** Always invoked on the EDT - PropertyPoller does all adb/parsing work on a background thread. */
public interface PollListener {

    void onUpdate(DumpResult result, List<PropertySnapshot> changed);

    void onError(String message);
}
