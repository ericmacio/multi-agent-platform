# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Recommendations
- Be always concise and not verbose

## Project structure

- Project global specifications can be found in @docs/SPECS.md file
- The backend folder will contains all backend related information including documentation and source code
- The frontend folder will contains the frontend application and the related documentation

## Local environment

You are running in a laptop Windows machine with no admin rights. Don't try to run Linux command or access Linux files

## Status

Backend and frontend developpement are done for the first release. We are now doing some adjustments

### Backend

Here are all the steps already executed:
- Requirements list has been specified for the backend (@backend/specs/REQS.md).
- The software design document (@backend/design/SW-DESIGN.md) has been created.
- The API contract has been written to @openapi.yaml — the single source of truth shared between backend and frontend.
- The backend EPICs have been created in @backend/backlog/EPICS.md.
- EPIC-01, EPIC-02, EPIC-03 have been implemented. 
- A code review for the 3 first EPICs has been performed. Result is stored in @backend/analysis/CODE-REVIEW.md
- High recommendations have been implemented according to @backend/backlog/EPIC-CR1-US.md file
- EPIC-04, 05, 06 and 07 have been implemented
- EPIC-08 have been implemented
- EPIC-09 have been implemented
- EPIC-10 and EPIC-11 have been implemented
- EPIC-12 has been implemented
- EPIC-13 has been implemented
- EPIC-14 has been implemented
- EPIC-15 has been implemented
- File @backend/backlog/US-STATUS.md has been updated

### Frontend

- User stories of EPIC-01 has been implemented
- EPIC-02 and EPIC-03 have been implemented
- EPIC-04 and EPIC-05 have been implemented
- EPIC-06 has been implemented
- EPIC-07 has been implemented
- EPIC-08 has been implemented
- EPIC-09 has been implemented
- EPIC-10 has been implemented
- EPIC-11 has been implemented
- We are building a first MVP aligned with current backend implementation