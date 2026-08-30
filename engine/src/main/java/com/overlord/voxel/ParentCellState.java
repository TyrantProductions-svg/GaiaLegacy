package com.overlord.voxel;

public sealed interface ParentCellState
        permits FullCellState, DetailCellState {}
