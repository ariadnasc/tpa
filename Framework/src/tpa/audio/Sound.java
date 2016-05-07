package tpa.audio;

import com.sun.istack.internal.NotNull;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Created by german on 28/03/2016.
 */
public class Sound {

    /** sample data */
    private Buffer samples = null;

    /** sampling frequency */
    private int samplingRate = 16000;

    /** stereo sound flag */
    private boolean stereo = false;

    /** state */
    private boolean dirty = true;

    public Sound () {
        this.samplingRate = 16000;
        this.stereo = false;
    }

    /**
     * Set to true to tell renderer this sound is stereo
     * @param b true if sound is stereo
     */
    public void setStereo (boolean b) {
        stereo = b;
        setDirty(true);
    }

    /**
     * Wether the sound is stereo or not
     * @return true if sound is stereo, false is it is mono
     */
    public boolean isStereo () {
        return stereo;
    }

    /**
     * Set samples
     * @param samples
     */
    public void setSamples (Buffer samples) {
        if (samples == null)
            throw new IllegalArgumentException();
        this.samples = samples;
        setDirty(true);
    }

    /**
     * Get typed samples
     * @param type sample type
     * @param <T> type declaration
     * @return samples
     */
    public <T extends Buffer> T getSamples (Class<T> type) {
        return (T) samples;
    }


    /**
     * Get buffer containing samples
     * @return sound samples
     */
    public Buffer getSamples () {
        return samples;
    }

    /**
     * Get state
     * @return
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Set state to signal sound renderer
     * @param dirty new state
     */
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    /**
     * Get sampling rate in Hz
     * @return sampling rate
     */
    public int getSamplingRate() {
        return samplingRate;
    }

    /**
     * Set sampling rate
     * @param samplingRate Hz
     */
    public void setSamplingRate(int samplingRate) {
        this.samplingRate = samplingRate;
        setDirty(true);
    }
}
