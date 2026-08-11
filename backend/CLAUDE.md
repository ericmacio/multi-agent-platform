# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Backend initial specifications and guidelines

The following folders will help in all design and implementation tasks:
- Specifications file @docs/SPECS.md contains all initial backend specifications. It must be used as a base for the design and development of the solution.
- Java coding guidelines are in @docs/JAVA-CODING-STANDARD.md file and must be followed for design and implementation.
- Exceptions handling is descriobed in @Docs/EXCEPTIONS.md and must be strictly followed when designing and implementating exceptions handling.

---

## Backend documentations

The following directories structure will be used:
- docs folder contains initial product specifications and guidelines. This folder must NOT be modified. Only used in read mode
- requirements folder will contains the list of requirements stored in @requirements/REQS.md file. This file will be created by Claude when requested
- design folder will contain the software design of the backend solution in design/SW-DESIGN.md file. It will be provisionned by Claude when requested from the list of requirements (@requirements/REQS.md)
- backlog folder will contain the list of EPICs in @backlog/EPICS.md file and the list of each EPIC user stories in each EPIC referenced file: EPIC-<ref>-US.md
- backlog folder will also contains a US-STATUS.md file that will track the status of all backend user stories.
- analysis folder will be used to stored reviews performed by Claude on demand.
- implementation choices that are worth flagging are stored in implementation/DESIGN-CHOICES.md. Use it to save your coments about implementation choices
- A summary file has been created in implementation/SUMMARY.md. This file must be used to put the summary after EACH implementation task. Be very concise and put only information about files that have been created or modified with a short description

---

## Workflow

A spec-driven approach will be used during the project:
- Create list of requirements in requirements/REQ.md from backend specifications (@docs/SPECS.md). The requirements document MUST not define any REST endpoints. They will be specified in the next step in the SW-DESIGN document
- Create backend software design in design/SW-DESIGN.md from specifications and requirements. This document must explain architecture, project structure, REST endpoints definition and any other topics related to the design of the solution. It will help in creating the API contract.
- Create API contract ../openapi.yaml describing openAPI specifications to be shared between frontend and backend
- Create EPICs and user stories in backlog folder
- Keep backlog/US-STATUS.md and backlog/EPICS.md files updated after each US creation or implementation
- Implement user stories and store specific implementation choices that are worth flagging in backlog/DESIGN-CHOICES.md file
- After each US implementation store a short summary of what has been done in implementation/SUMMARY.md file
- Review processing can be done each phase

---

## Conventions

### Dev-only / debug controllers

Dev-only smoke or probe controllers (e.g. `PingController`, `MeProbeController`) MUST live in `src/test/java` only. They MUST NOT be added to `src/main/java` and gated by `@Profile("dev")` — running the packaged JAR with `SPRING_PROFILES_ACTIVE=dev` would expose them. Test classpath is the scoping mechanism; the `LayeringArchTest` rule `no_rest_controllers_live_under_infrastructure_web_dev_on_main_classpath` enforces this at build time. The default Spring profile is the production profile; `dev` is for local development and tests only.
