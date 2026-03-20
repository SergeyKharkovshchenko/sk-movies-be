# SK Movies Backend — Claude Spec

## Project Overview

Spring Boot 3.5 REST API backend for a movie recommendation system. Java 17, Maven. Primary DB is Neo4j (graph), with MongoDB configured but unused.

## Architecture

```
src/main/java/com/moviesApp/
├── skMoviesApp.java          # Entry point
├── controllers/              # REST layer
├── service/                  # Business logic
├── entities/                 # Domain models (User, Movie, Comment)
├── config/                   # Neo4jConfig, CorsConfig
└── model/                    # Status enum
```

## Domain Entities

- **Movie**: `movieId`, `movieTitle`, `duration`, `genre0/1/2`, `plotSummary`, `averageRating`
- **User**: `userId`, `numberOfReviews`
- **Comment** (review): `userId`, `movieId`, `rating`, `reviewText`, `reviewSummary`, `reviewDate`, `isSpoiler`

## Services

| Service | Responsibility |
|---------|---------------|
| `Neo4jService` | Generic Cypher query abstraction (`queryList`, `querySingle`, `exists`) |
| `MovieService` | Movie listing with aggregated ratings/genres |
| `UserService` | User listing, embedding generation, similarity search |
| `CommentsService` | CRUD for reviews by movie or user |

## REST Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/getAllMovies` | Top 20 movies |
| GET | `/getAllUsers` | Top 20 users by review count |
| GET | `/getAllComments` | Comments for hardcoded movie `tt0110912` |
| GET | `/comments/movie/{movieId}` | Comments for a movie |
| GET | `/comments/user/{userId}` | Comments by a user |
| POST | `/createRatingsEmbedding` | Generate per-movie embeddings (v1) |
| POST | `/createRatingsEmbedding2` | Average embedding across movies (v2) |
| POST | `/createRatingsEmbedding3` | Refactored v3 (extracted helpers) |
| GET | `/similar` | Find similar users via cosine similarity |

## Key External Integrations

- **Neo4j AuraDB**: Graph store. Bolt protocol. Env vars: `NEO4J_URI`, `NEO4J_USER`, `NEO4J_PASSWORD`.
- **Jina AI** (`https://api.jina.ai/v1/embeddings`): Generates 1024-dimension text embeddings from movie titles/plots. Key currently hardcoded in `UserService` — should be moved to env.
- **Neo4j GDS**: Used for cosine similarity in `getSimilarUsers()`.

## Neo4j Graph Relationships

- `REVIEWED_FROM_CSV` — user reviewed a movie
- `HAS_PROFILE_EMBED` — user node has a profile embedding
- `HAS_MOVIE_EMBED` — user node has per-movie embeddings

## Embedding Pipeline (UserService)

1. Fetch user's top 9 rated movies (`fetchUserTopMovies`)
2. Call Jina API to embed movie titles/plots (`generateMovieEmbeddings`)
3. Average all embeddings into a single 1024-dim vector (`computeAverageEmbedding`)
4. Store on user node in Neo4j
5. Query similar users via GDS cosine similarity (`getSimilarUsers`)

## Environment Variables

```
NEO4J_URI
NEO4J_USER
NEO4J_PASSWORD
MONGODB_URI          # configured but unused
MONGODB_DATABASE
JINA_API_KEY         # currently hardcoded in UserService — move to env
```

## Known Issues / Tech Debt

- Jina API key hardcoded in `UserService.java` (lines ~202, ~320, ~434)
- `getAllComments` has a hardcoded movie ID (`tt0110912`)
- MongoDB dependency is configured but not used
- Three versions of embedding endpoints exist (`Safe`, `Safe2`, `Safe3`) — cleanup pending

## CORS

Allows origins:
- `https://*.vercel.app` (production frontend)
- `http://localhost:*` (local dev)
