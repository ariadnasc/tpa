package game;

import activity.Activity;
import activity.MonkeyLocation;

/**
 * Created by germangb on 12/04/16.
 */
public enum GameActivity {

    /** Monkey location */
    Monkey (new MonkeyLocation());

    /** referenced game activity */
    private activity.Activity activity;

    GameActivity(activity.Activity activity) {
        this.activity = activity;
    }

    /**
     * Get eumarated activity
     * @return game activity
     */
    public Activity getActivity() {
        return activity;
    }
}
