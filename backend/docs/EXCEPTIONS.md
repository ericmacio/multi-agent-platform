# EXCEPTIONS.md
Guidelines for Exceptions handling implementation when using a hexagonal architecture with Spring Boot.

---

## Hexagonal Architecture
3 main zones:
- Domain (core): business rules
- Application (use cases): orchestration
- Infrastructure (adapters): API, Database, messaging
- Main principle: domain must not depend on anything

---

## Main Principle for Exceptions
Exceptions must follow layers. No technical exceptions in the domain

---

## Recommended typology

### Business exceptions
Live in the core
Examples:
- BusinessRuleViolationException
- OrderNotFoundException
- InsufficientBalanceException
Rules:
- Not dependent on Spring
- No HTTP code
- Expresses a business rule

### Application exceptions
Live in application layer mainly for orchestration cases
Example:
- UseCaseExecutionException

### Technical exceptions
Live in infrastructure layer
Used for Database, API, Messaging, etc ...\
Example:
- ExternalServiceException
- DatabaseAccessException

### HTTP handling
Live in REST adapter
Typically contains the following:
```
@RestControllerAdvice
public class GlobalExceptionHandler { ... }
```

### Exception flux
Domain:
```java
throw new OrderNotFoundException(orderId);
```
@RestControllerAdvice:
```java
@ExceptionHandler(OrderNotFoundException.class)
public ResponseEntity<?> handle(...) {
return Response Entity.status(404).body(...);
}
```

### Anti-patterns
DO NOT DO:
- Put all exceptions in a single package
- Use Spring exceptions in the domain 
- Forward technical exceptions to the user
- Catch and ignore exceptions
- Return generic exceptions

### Example of pattern
```java
public abstract class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
```
then:
```java
public class OrderNotFoundException extends BusinessException {}
```

on REST side:
- mapping type -> HTTP code
- centralized handler

