package br.com.lucaslima.enginerules.adapter.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionSyntaxException;

final class Lexer {

    private static final Set<String> TWO_CHAR_SYMBOLS = Set.of("==", "!=", "<=", ">=", "&&", "||");
    private static final String SINGLE_CHAR_SYMBOLS = "()[]{},.:?!<>+-*/%#";

    private final String source;
    private int position;

    private Lexer(String source) {
        this.source = source;
    }

    static List<Token> tokenize(String source) {
        return new Lexer(source).run();
    }

    private List<Token> run() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (position >= source.length()) {
                tokens.add(new Token(Token.Type.END, "", null, position));
                return tokens;
            }
            tokens.add(next());
        }
    }

    private Token next() {
        char current = source.charAt(position);
        if (Character.isDigit(current)) {
            return number();
        }
        if (current == '\'' || current == '"') {
            return string(current);
        }
        if (Character.isLetter(current) || current == '_') {
            return identifier();
        }
        if (position + 1 < source.length() && TWO_CHAR_SYMBOLS.contains(source.substring(position, position + 2))) {
            Token token = new Token(Token.Type.SYMBOL, source.substring(position, position + 2), null, position);
            position += 2;
            return token;
        }
        if (SINGLE_CHAR_SYMBOLS.indexOf(current) >= 0) {
            return new Token(Token.Type.SYMBOL, String.valueOf(current), null, position++);
        }
        throw new ExpressionSyntaxException("unexpected character '" + current + "' at position " + position);
    }

    private Token number() {
        int start = position;
        boolean decimal = false;
        while (position < source.length() && Character.isDigit(source.charAt(position))) {
            position++;
        }
        if (position + 1 < source.length() && source.charAt(position) == '.' && Character.isDigit(source.charAt(position + 1))) {
            decimal = true;
            position++;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
        }
        if (position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
            decimal = true;
            position++;
            if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                position++;
            }
            int exponentStart = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            if (exponentStart == position) {
                throw new ExpressionSyntaxException("malformed number at position " + start);
            }
        }
        String text = source.substring(start, position);
        Object literal;
        try {
            literal = decimal ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
        } catch (NumberFormatException overflow) {
            throw new ExpressionSyntaxException("number out of range at position " + start);
        }
        return new Token(Token.Type.NUMBER, text, literal, start);
    }

    private Token string(char quote) {
        int start = position++;
        StringBuilder value = new StringBuilder();
        while (position < source.length()) {
            char current = source.charAt(position++);
            if (current == quote) {
                return new Token(Token.Type.STRING, source.substring(start, position), value.toString(), start);
            }
            if (current == '\\') {
                if (position >= source.length()) {
                    break;
                }
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case '\\', '\'', '"' -> value.append(escaped);
                    default -> throw new ExpressionSyntaxException("unknown escape '\\" + escaped + "' at position " + (position - 2));
                }
            } else {
                value.append(current);
            }
        }
        throw new ExpressionSyntaxException("unterminated string starting at position " + start);
    }

    private Token identifier() {
        int start = position;
        while (position < source.length()
                && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '_')) {
            position++;
        }
        return new Token(Token.Type.IDENTIFIER, source.substring(start, position), null, start);
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }
}
