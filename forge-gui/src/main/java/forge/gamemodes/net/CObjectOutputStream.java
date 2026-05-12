package forge.gamemodes.net;

import forge.trackable.Tracker;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;

public class CObjectOutputStream extends ObjectOutputStream {
    static final int TYPE_THIN_DESCRIPTOR = 1;

    private final Tracker tracker;

    CObjectOutputStream(OutputStream out, boolean replaceTrackables, Tracker tracker) throws IOException {
        super(out);
        this.tracker = tracker;
        if (replaceTrackables) {
            enableReplaceObject(true);
        }
    }

    @Override
    protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
        //we only pass this and the decoder will lookup in the stream (faster method both mobile and desktop)
        write(TYPE_THIN_DESCRIPTOR);
        writeUTF(desc.getName());
    }

    @Override
    protected Object replaceObject(Object obj) throws IOException {
        return TrackableSerializer.replace(obj, tracker, false);
    }
}
