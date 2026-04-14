package com.sprinklerapp;

import com.sprinklerapp.BuildConfig;

public class FirebaseConfig {

    public static final String BASE_URL = BuildConfig.BASE_URL;
    public static final String SECRET   = BuildConfig.SECRET;
    public static final String PATH     = BuildConfig.PATH;

    /** Kompletná URL vrátane cesty, pripravená na HTTP volania */
    public static String fullUrl() {
        return "https://" + BASE_URL + PATH;
    }

    private FirebaseConfig() {} // statická trieda, nedá sa inštancovať
}
