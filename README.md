# SK Movies Backend

A Spring Boot 3.5 REST API backend for a movie recommendation system, built with Java 17 and Maven.
It uses Neo4j (graph database) to store movies, users, and reviews, and integrates with Jina AI to generate embeddings for personalized user similarity search.
The API exposes endpoints for browsing movies, managing reviews, and finding similar users based on their viewing and rating history.

## Endpoints

GET
localhost:8080/getAllUsers
localhost:8080/getMoviesByActor
localhost:8080/78/comments
localhost:8080/getMoviesNamesByActor
localhost:8080/getMoviesByActor

POST
localhost:8080/createUser
{
    "username": "user 2"
}
localhost:8080/78/comments
{
    "content": "content 123456",
    "userId": "69382d86d2e2570e1da11a9b"
}