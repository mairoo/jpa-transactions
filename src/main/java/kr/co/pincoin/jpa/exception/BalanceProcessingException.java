package kr.co.pincoin.jpa.exception;

public class BalanceProcessingException extends RuntimeException {
    public BalanceProcessingException(String message) {
        super(message);
    }

    public BalanceProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}