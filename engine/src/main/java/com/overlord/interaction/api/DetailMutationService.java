package com.overlord.interaction.api;

public interface DetailMutationService {
    DetailMutationResult convertFullToDetail(
            FullToDetailRequest request);

    DetailMutationResult setSubVoxel(
            DetailMutationRequest request);

    DetailMutationResult compactDetailToFull(
            DetailToFullRequest request);
}
