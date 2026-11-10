package com.shitulelv.aicollab.common.ai.token;

public record TokenCount(
        int count,
        TokenCountSource source,
        String basis,
        boolean estimated) {

    public static TokenCount exact(int count, String basis) {
        return new TokenCount(Math.max(0, count), TokenCountSource.NATIVE, basis, false);
    }

    public static TokenCount tokenizer(int count, String basis) {
        return new TokenCount(Math.max(0, count), TokenCountSource.TOKENIZER, basis, false);
    }

    public static TokenCount estimated(int count, String basis) {
        return new TokenCount(Math.max(0, count), TokenCountSource.ESTIMATED, basis, true);
    }

    public static TokenCount zero() {
        return new TokenCount(0, TokenCountSource.ESTIMATED, "EMPTY", false);
    }

    public TokenCount add(TokenCount other) {
        if (other == null) return this;
        boolean isEstimated = this.estimated || other.estimated;
        TokenCountSource finalSource = (this.source == TokenCountSource.NATIVE && other.source == TokenCountSource.NATIVE)
                ? TokenCountSource.NATIVE
                : (this.source == TokenCountSource.TOKENIZER && other.source == TokenCountSource.TOKENIZER)
                ? TokenCountSource.TOKENIZER
                : TokenCountSource.ESTIMATED;
        String combinedBasis = this.basis + "+" + other.basis;
        return new TokenCount(this.count + other.count, finalSource, combinedBasis, isEstimated);
    }
}
