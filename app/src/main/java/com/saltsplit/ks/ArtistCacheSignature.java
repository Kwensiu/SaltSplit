package com.saltsplit.ks;

final class ArtistCacheSignature {
    static final String RULE_CACHE_VERSION = "artist-dao-normalize-v5";

    private ArtistCacheSignature() {
    }

    static String build(String separatorsText, String excludedArtistsText) {
        return build(separatorsText, excludedArtistsText, 0L);
    }

    static String build(
            String separatorsText,
            String excludedArtistsText,
            long applyRequestVersion) {
        return RULE_CACHE_VERSION
                + "\u001F"
                + applyRequestVersion
                + "\u001F"
                + separatorsText
                + "\u001F"
                + excludedArtistsText;
    }
}
