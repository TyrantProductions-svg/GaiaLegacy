package com.overlord.interaction.api;

public interface DetailMutationService {
    DetailMutationResult convertFullToDetail(
            FullToDetailRequest request);

    DetailMutationResult setSubVoxel(
            DetailMutationRequest request);

    DetailMutationResult removeDetailParent(
            RemoveDetailParentRequest request);

    DetailMutationResult sculptParentSubVoxel(
            SculptParentSubVoxelRequest request);

    DetailMutationResult compactDetailToFull(
            DetailToFullRequest request);
}
