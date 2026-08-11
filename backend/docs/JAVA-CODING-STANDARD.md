# Java coding standard
This file provide guidance for java coding

## General
Adopt functional style programming instead of imperative mode whenever possible
Prefer clarity to cleverness
Immutable by default; minimize shared mutable state
Fail fast with meaningful exceptions
Consistent naming and package structure
Please avoid to generate too many boilerplate.
Use record types for DTOS
Prefer constructor injection over @Autowired field injection
Use record whenever possible to reduce boilerplate. Do not use lombok even if record is not possible
Keep methods short and focused; extract helpers
Order members: constants, fields, constructors, public methods, protected, private
Use streams for transformations, keep pipelines short

## Testing Expectations
JUnit 5 + AssertJ for fluent assertions
Mockito for mocking; avoid partial mocks where possible

## Exceptions
Guidelines for exceptions handling can be found in @EXCEPTIONS.md