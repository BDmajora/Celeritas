package com.bdmajora.impetus.umbra.shaderpack.parsing;

// Tiny consuming parser over a single line; each takeX method pulls a token off the front and advances the cursor
// Ported verbatim from Umbra - the option parser depends on its exact whitespace/word/number semantics
public class ParsedString {
    private String text;

    public ParsedString(String text) {
        this.text = text;
    }

    // Consumes the token if it is next
    public boolean takeLiteral(String token) {
        if (!text.startsWith(token)) {
            return false;
        }

        text = text.substring(token.length());

        return true;
    }

    // Consumes at least one whitespace character
    public boolean takeSomeWhitespace() {
        if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) {
            return false;
        }

        // TODO: We do a double sided trim
        text = text.trim();

        return true;
    }

    // Consumes a leading // and the spaces after it
    public boolean takeComments() {
        if (!text.startsWith("//")) {
            return false;
        }

        // Remove the initial two comment slashes
        text = text.substring(2);

        // Remove any additional comment slashes
        while (text.startsWith("/")) {
            text = text.substring(1);
        }

        return true;
    }

    // Whether the remainder contains the text
    public boolean currentlyContains(String text) {
        return this.text.contains(text);
    }

    // Nothing left
    public boolean isEnd() {
        return text.isEmpty();
    }

    // Everything remaining
    public String takeRest() {
        return text;
    }

    // Consumes a fixed count
    private String takeCharacters(int numChars) {
        String result = text.substring(0, numChars);
        text = text.substring(numChars);

        return result;
    }

    // An identifier, or null
    public String takeWord() {
        if (isEnd()) {
            return null;
        }

        int position = 0;

        for (char character : text.toCharArray()) {
            if (!Character.isDigit(character)
                    && !Character.isAlphabetic(character)
                    && character != '_') {
                break;
            }

            position += 1;
        }

        if (position == 0) {
            return null;
        }

        return takeCharacters(position);
    }

    // A numeric literal, or null
    public String takeNumber() {
        if (isEnd()) {
            return null;
        }

        int position = 0;
        int digitsBefore = 0;

        if (text.charAt(0) == '-') {
            position += 1;
        }

        while (position < text.length() && Character.isDigit(text.charAt(position))) {
            position += 1;
            digitsBefore += 1;
        }

        if (digitsBefore == 0) {
            return null;
        }

        if (position < text.length()) {
            char next = text.charAt(position);

            if (next == '.') {
                position += 1;

                int digitsAfter = 0;

                while (position < text.length() && Character.isDigit(text.charAt(position))) {
                    position += 1;
                    digitsAfter += 1;
                }

                if (digitsAfter == 0) {
                    return null;
                }

                if (position < text.length()) {
                    next = text.charAt(position);

                    if (next == 'f' || next == 'F') {
                        position += 1;
                    }
                }
            } else if (next == 'f' || next == 'F') {
                position += 1;
            }
        }

        return takeCharacters(position);
    }

    // Either, or null
    public String takeWordOrNumber() {
        String number = takeNumber();

        if (number == null) {
            return takeWord();
        }

        return number;
    }
}
