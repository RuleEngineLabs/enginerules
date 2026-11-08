package br.com.lucaslima.enginerules.application.shared;

public record PageRequest(int pageNumber, int pageSize) {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    public PageRequest {
        if (pageNumber < 1) {
            throw new InvalidRequestException("pageNumber starts at 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public static PageRequest of(Integer pageNumber, Integer pageSize) {
        return new PageRequest(pageNumber == null ? 1 : pageNumber, pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
    }

    public int offset() {
        return (pageNumber - 1) * pageSize;
    }
}
