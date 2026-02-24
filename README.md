
# Sporty Betting Settlement (Skeleton)

## Requirements
- Java 21
- Docker

## Run Kafka
docker compose up -d

## Run Application
./gradlew bootRun

## Test Endpoint
curl -X POST http://localhost:8080/api/event-outcomes      -H "Content-Type: application/json"      -d '"Test Event"'
