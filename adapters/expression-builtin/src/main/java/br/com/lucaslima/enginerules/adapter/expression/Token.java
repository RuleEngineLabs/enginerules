package br.com.lucaslima.enginerules.adapter.expression;

record Token(Type type, String text, Object literal, int position) {

    enum Type {
        NUMBER,
        STRING,
        IDENTIFIER,
        SYMBOL,
        END
    }

    boolean isSymbol(String symbol) {
        return type == Type.SYMBOL && text.equals(symbol);
    }

    boolean isKeyword(String keyword) {
        return type == Type.IDENTIFIER && text.equals(keyword);
    }
}
