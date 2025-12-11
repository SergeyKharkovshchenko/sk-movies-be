package com.moviesApp.common;

import com.moviesApp.model.CategoryStatus;

public class CategoryDto {

    private Long categoryId;

    private CategoryStatus categoryName;

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public CategoryStatus getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(CategoryStatus categoryName) {
        this.categoryName = categoryName;
    }
}
