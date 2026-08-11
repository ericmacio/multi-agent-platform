# SPECS.md

This file provides the list of specifications for the backend module

## Purpose

We want to build a multi-agent platform backend API application that will allow to create, configure and manage several agents.
The multi-agent platform will serve as an API set for a frontend client (UI) and external applications as a Web Service
This backend must allow the following:
- Multi-user access and management. 
- Creation, configuration and management of AI agents.
- A chat interface.
- The user will have the possibility to view / restart / delete a past chat conversation.

## Main modules

### Agents

An agent will be specified by a set of parameters:
- Name of the agent. Must be unique per user. Two user can have an agent with the same name. Mandatory parameter
- List of available tools (default: empty)
- Enable/Disable MCP access (default: disable)
- A description field. This field will allow the other agents to know the service provided by this agent. This will be particularly useful in the case of agent delegation operations (agent team). MAndatory
- A system prompt field. This will give the context of the agent, what it can do and how to do it. Mandatory
- The memory size: This size will define the number of messages (user, asistant, ...) kept in the context of the agent (default: 12, max: 36)
- The user owner: Who created the agent. All agents will be private and cannot be visibled and used by another user
- Team delegation: An agent will also have the possibility to delegate a task to another agent (member of its team). It will use the description field fo that. (default: empty)
- A user will be able to perform basic CRUD operations on all agents he owns: Create, modify, Delete, ... When deleting an agent, all conversations related to it will also be deleted

### Chat

A chat interface will be provided to the user:
- The user will have to possibility to launch a chat with any of the agent he owns. A user cannot launch a chat with an agent he didn't create.
- The platform will store all conversations for a future usage.
- The user will have the possibility to view / restart / delete a past chat conversation
- The user will be able to start a new conversation wityh an agent even if he already have ongoing conversation
- Each conversation SHALL have a `title` field. The title SHALL be **auto-derived from the first non-empty user message**

### Tools

Tools will be used to provide some specific capabilities to the agents:
- Tools will be static. They will be listed at startup
- Tools will be specific functions (class and method) that will be linked to an agent by configuration
- Tools will be conformed to Spring AI specifications

### MCP servers

MCP servers will give additional features to an agent:
- They will be specified by configuration (application.properties)
- They will be enabled/disabled individually to agents
- `brave-search` (web search), `filesystem` (local file access) will be preconfigured
- The `brave-search` API key will be supplied by the "BRAVE_API_KEY" environment variable

Configuration example for `brave-search`:

  ```properties
  spring.ai.mcp.client.stdio.connections.brave-search.command=npx
  spring.ai.mcp.client.stdio.connections.brave-search.args=-y, @modelcontextprotocol/server-brave-search
  spring.ai.mcp.client.stdio.connections.brave-search.env.BRAVE_API_KEY=${BRAVE_API_KEY}
  ```

---

## Architecture

### Overview

- Make the architecture and project structure simple and clear. Do not over complicate
- This multi-agent platform will use Spring Boot 4.0.6, Java 17 as software language backend and **Spring AI 1.1.0**
to expose configurable "agents" over REST.
- The design of the solution will propose a clear separation of the technical framework from business rules.
- An hexagonal architecture is the preferred choice if relevant but alternative can be proposed
- A persistent layout will be used to store the required business entity
- Conversation will be saved in a dedicated storage.

### LLM

- The default and initial LLM provider SHALL be **OpenAI**.
- The default model SHALL be **`gpt-4o-mini`**.
- The OpenAI credentials SHALL be supplied via the **`OPENAI_API_KEY`** environment variable
- Design SHALL stay provider-agnostic so that other LLM provider can be added later

### Database

A PostgreSQL database will be the preferred choice. Database migrtion will be managed with Flyway

### Auth model

2 different authentication models will be used:
- **API-key mode** based on client-id / API-KEY
-  **JWT mode** — Bearer <token> from Authorization header.
    - JWT will be issued by backend
    - Username (email) will be part of the JWT claims.
    - Default lifetime is 30 min.
    - No need for refresh token
    - A user cannot have multiple **simultaneously valid** tokens. 
- API-key headers will be **`X-Api-Key`** + **`X-Client-Id`** 

### Password

- Minimum length of **10 characters**,
- At least **one capital letter** (`A`–`Z`),
- At least **one special character**
- User passwords SHALL be hashed using **BCrypt** before persistence.
- Plain-text passwords MUST NOT be stored or logged.
- A authenticated user can change their own password

### Streaming

The chat response path must be reactive. Streaming endpoints SHALL use **Server-Sent Events (SSE)** with content type `text/event-stream`.

### Signup

Only admin user will be allowed to create user. A specific role flag will exist in the user database

### Rate limiter

A Rate limiter filter will be implemented to limit incoming traffic. Bucket4j is the preferred solution for implementation. The rate limitation will be global (no IP or user based)

### Development conventions

Make sure to follow the guidelines as specified in the docs folder (i.e. Java coding standard, exceptions, etc ...)
- Guidelines for java coding can be found in @JAVA-CODING-STANDARD.md
- Guidelines for exception handling can be found in @EXCEPTIONS.md

### Frontend client

- The API proposed must then be compatible with a frontend developped using ReactJs library (streaming)
- **CORS** SHALL be enabled for the configured frontend origin(s). The allow-list of origins SHALL be configurable via Spring properties

---

## Deployment

### HTTP

The REST API will be first expose a a set of HTTP endpoints. No HTTPS will be available on the local dev environment. It will be porposed as a secondary step only in the production environment.

### AWS cloud

- The backend API will be deployed into AWS cloud environment. You can then make use of some specific AWS service (EC2, S3, RDS, dynamoDb) if needed.
- The preferred target for the deployment will be an EC2 instance via a simple copy of the jar file.
- No need for a deployment on EKS/ECS/Fargate.
- No need for docker container
- Make sure to separate technical infra constraints from business

### local environment

Local environment will not allow to use any docker container. A postgreSQL server is however available locally
The local postgreSQL postgres user password is "olinka"
You can also use the emk/emk account for your tests
A database has been created on the local PostgreSQL with the name: "emk"

