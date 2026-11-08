package br.com.lucaslima.enginerules.adapter.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.execution.spi.ExpressionSyntaxException;

final class Parser {

    private static final Set<String> RESERVED_WORDS = Set.of("true", "false", "null", "and", "or", "not", "in");

    private final List<Token> tokens;
    private int position;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Node parse(String source) {
        Parser parser = new Parser(Lexer.tokenize(source));
        Node root = parser.expression();
        if (parser.peek().type() != Token.Type.END) {
            throw parser.unexpected();
        }
        return root;
    }

    private Node expression() {
        Node condition = or();
        if (acceptSymbol("?")) {
            Node whenTrue = expression();
            expectSymbol(":");
            Node whenFalse = expression();
            return new Node.Conditional(condition, whenTrue, whenFalse);
        }
        return condition;
    }

    private Node or() {
        Node left = and();
        while (acceptSymbol("||") || acceptKeyword("or")) {
            left = new Node.Logical("||", left, and());
        }
        return left;
    }

    private Node and() {
        Node left = equality();
        while (acceptSymbol("&&") || acceptKeyword("and")) {
            left = new Node.Logical("&&", left, equality());
        }
        return left;
    }

    private Node equality() {
        Node left = comparison();
        while (true) {
            if (acceptSymbol("==")) {
                left = new Node.Binary("==", left, comparison());
            } else if (acceptSymbol("!=")) {
                left = new Node.Binary("!=", left, comparison());
            } else {
                return left;
            }
        }
    }

    private Node comparison() {
        Node left = additive();
        while (true) {
            String operator = comparisonOperator();
            if (operator == null) {
                return left;
            }
            left = new Node.Binary(operator, left, additive());
        }
    }

    private String comparisonOperator() {
        for (String symbol : List.of("<=", ">=", "<", ">")) {
            if (acceptSymbol(symbol)) {
                return symbol;
            }
        }
        if (acceptKeyword("in")) {
            return "in";
        }
        if (peek().isKeyword("not") && peekAhead().isKeyword("in")) {
            position += 2;
            return "not in";
        }
        return null;
    }

    private Node additive() {
        Node left = multiplicative();
        while (true) {
            if (acceptSymbol("+")) {
                left = new Node.Binary("+", left, multiplicative());
            } else if (acceptSymbol("-")) {
                left = new Node.Binary("-", left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Node multiplicative() {
        Node left = unary();
        while (true) {
            if (acceptSymbol("*")) {
                left = new Node.Binary("*", left, unary());
            } else if (acceptSymbol("/")) {
                left = new Node.Binary("/", left, unary());
            } else if (acceptSymbol("%")) {
                left = new Node.Binary("%", left, unary());
            } else {
                return left;
            }
        }
    }

    private Node unary() {
        if (acceptSymbol("!") || acceptKeyword("not")) {
            return new Node.Not(unary());
        }
        if (acceptSymbol("-")) {
            return new Node.Negate(unary());
        }
        return postfix(primary());
    }

    private Node postfix(Node target) {
        Node current = target;
        while (true) {
            if (acceptSymbol(".")) {
                current = new Node.Member(current, expectIdentifier());
            } else if (acceptSymbol("[")) {
                Node index = expression();
                expectSymbol("]");
                current = new Node.Index(current, index);
            } else {
                return current;
            }
        }
    }

    private Node primary() {
        Token token = advance();
        switch (token.type()) {
            case NUMBER, STRING -> {
                return new Node.Literal(token.literal());
            }
            case IDENTIFIER -> {
                return identifier(token);
            }
            case SYMBOL -> {
                return symbolPrimary(token);
            }
            default -> throw unexpected(token);
        }
    }

    private Node identifier(Token token) {
        switch (token.text()) {
            case "true" -> {
                return new Node.Literal(Boolean.TRUE);
            }
            case "false" -> {
                return new Node.Literal(Boolean.FALSE);
            }
            case "null" -> {
                return new Node.Literal(null);
            }
            default -> {
            }
        }
        if (RESERVED_WORDS.contains(token.text())) {
            throw unexpected(token);
        }
        if (acceptSymbol("(")) {
            return call(token);
        }
        return new Node.Variable(token.text());
    }

    private Node call(Token name) {
        Builtin function = Builtin.named(name.text())
                .orElseThrow(() -> new ExpressionSyntaxException("unknown function '" + name.text() + "' at position " + name.position()));
        List<Node> arguments = new ArrayList<>();
        if (!acceptSymbol(")")) {
            do {
                arguments.add(expression());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        if (!function.accepts(arguments.size())) {
            throw new ExpressionSyntaxException("function '" + function.contractName() + "' does not accept "
                    + arguments.size() + " argument(s)");
        }
        return new Node.Call(function, arguments);
    }

    private Node symbolPrimary(Token token) {
        if (token.isSymbol("(")) {
            Node inner = expression();
            expectSymbol(")");
            return inner;
        }
        if (token.isSymbol("#")) {
            return new Node.Current();
        }
        if (token.isSymbol("[")) {
            List<Node> items = new ArrayList<>();
            if (!acceptSymbol("]")) {
                do {
                    items.add(expression());
                } while (acceptSymbol(","));
                expectSymbol("]");
            }
            return new Node.ListLiteral(items);
        }
        if (token.isSymbol("{")) {
            Map<String, Node> entries = new LinkedHashMap<>();
            if (!acceptSymbol("}")) {
                do {
                    Token key = advance();
                    if (key.type() != Token.Type.IDENTIFIER && key.type() != Token.Type.STRING) {
                        throw unexpected(key);
                    }
                    expectSymbol(":");
                    entries.put(key.type() == Token.Type.STRING ? (String) key.literal() : key.text(), expression());
                } while (acceptSymbol(","));
                expectSymbol("}");
            }
            return new Node.MapLiteral(entries);
        }
        throw unexpected(token);
    }

    private String expectIdentifier() {
        Token token = advance();
        if (token.type() != Token.Type.IDENTIFIER) {
            throw unexpected(token);
        }
        return token.text();
    }

    private void expectSymbol(String symbol) {
        Token token = advance();
        if (!token.isSymbol(symbol)) {
            throw new ExpressionSyntaxException("expected '" + symbol + "' at position " + token.position()
                    + " but found '" + token.text() + "'");
        }
    }

    private boolean acceptSymbol(String symbol) {
        if (peek().isSymbol(symbol)) {
            position++;
            return true;
        }
        return false;
    }

    private boolean acceptKeyword(String keyword) {
        if (peek().isKeyword(keyword)) {
            position++;
            return true;
        }
        return false;
    }

    private Token advance() {
        Token token = peek();
        if (token.type() != Token.Type.END) {
            position++;
        }
        return token;
    }

    private Token peek() {
        return tokens.get(position);
    }

    private Token peekAhead() {
        return tokens.get(Math.min(position + 1, tokens.size() - 1));
    }

    private ExpressionSyntaxException unexpected() {
        return unexpected(peek());
    }

    private ExpressionSyntaxException unexpected(Token token) {
        if (token.type() == Token.Type.END) {
            return new ExpressionSyntaxException("unexpected end of expression");
        }
        return new ExpressionSyntaxException("unexpected '" + token.text() + "' at position " + token.position());
    }
}
