package com.ootruffle.clientmetics.render;

/** Dev autotest hooks the wardrobe screen implements, so the autotest can drive it without naming Kotlin classes. */
public interface WardrobeDev {

    /** Opens an equipped item's settings as if clicked; a negative ID goes back to the list. */
    void devSelectEquipped(int id);

}
