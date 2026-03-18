# Extract Functions from createRatingsEmbeddingSafe3

**Status:** Implementing...

## TODO Steps:
1. [x] Plan approved by user
2. [x] Add `fetchUserTopMovies` and `generateMovieEmbeddings` helpers (edit_file insert)
3. [x] Refactor `createRatingsEmbeddingSafe3` body (edit_file replace)
4. [x] Verify compilation (`mvn compile` successful)
5. [ ] Test functionality (endpoint call optional - POST /createRatingsEmbedding3?userId=ur4445210)
6. [x] Complete task

## Changes:
- Extract Neo4j movie fetch to `fetchUserTopMovies`
- Extract Jina API embedding generation to `generateMovieEmbeddings`
- Main method now orchestrates + saves profile embedding

