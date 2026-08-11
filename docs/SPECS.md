# SPECS.md

This file provides a set of specifications for the project

## Purpose

We want to build a multi-agent platform application that will allow to create, configure and manage several AI agents.
The multi-agent platform will be composed of a backend server serving a set of APIs and a frontend application that will allow
a user to execute actions like creating an agent, launching a chat, etc ...
The solution must allow the following:
- Multi-user access and management.
- Creation of AI agents. An agent will be specified by a set of parameters: name, set of tools, MCP enabled / disabled,
a description field, a system prompt field, the memory size, the user owner. An agent will also have the possibility
to delegate task to another agent (member of its team)
- A chat interface. The user will have to possibility to launch a chat with any of the agent. The platform will allow
to store all conversations for a future usage.
- The user will have the possibility to view / restart / delete a past chat conversation

## Architecture

This multi-agent platform will use Spring Boot, Java  for the backend and Spring AI
to expose configurable "agents" over REST.
The frontend will use ReactJs library and tailwind as CSS library
All detailed architecture information can be found in the respective backend and frontend directories

## API Contract

An API-first approach will be used. An openapi.yaml file will contain all information about the backend API
It will serve as a shared document between backend and frontend and contain the API contract
The single source of truth is `openapi.yaml` that will be maintanined up to date in the root directory
- Before writing any frontend code, read openapi.yaml
- All API calls must match the spec exactly

## Documentation

- Always refer to the backend documentation (specifications, requirements, design, ...) when working on the backend module
- Always refer to the frontend documentation and the API contrcat when working on the frontend module