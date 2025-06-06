package com.hnu.muwu.bean;

public class PhotoTag {

    private Integer id;
    private Integer userId;
    private String name;
    private String tag;

    public PhotoTag() {
    }

    public PhotoTag(Integer userId, String tag ,String name) {
        this.userId = userId;
        this.tag = tag;
        this.name = name;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
    public String getTag() {return tag;}
    public void setTag(String tag) {this.tag=tag;}
    public Integer getId() {return id;}
    public void setId(Integer id) {this.id=id;}

    @Override
    public String toString() {
        return "PhotoTag{" +
                "userId=" + userId +
                ", tagName='" + tag + '\'' +
                '}';
    }
}
