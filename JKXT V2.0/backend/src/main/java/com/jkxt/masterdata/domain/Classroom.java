package com.jkxt.masterdata.domain;

import com.jkxt.common.domain.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "classroom")
public class Classroom extends TenantEntity {

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  private String building;

  private String floor;

  private int capacity;

  @Column(name = "exam_capacity")
  private int examCapacity;

  @Column(name = "room_type", nullable = false)
  private String roomType = "standard";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private List<String> features = new ArrayList<>();

  @Column(nullable = false)
  private String status = "active";

  public Classroom() {
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getBuilding() {
    return building;
  }

  public void setBuilding(String building) {
    this.building = building;
  }

  public String getFloor() {
    return floor;
  }

  public void setFloor(String floor) {
    this.floor = floor;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }

  public int getExamCapacity() {
    return examCapacity;
  }

  public void setExamCapacity(int examCapacity) {
    this.examCapacity = examCapacity;
  }

  public String getRoomType() {
    return roomType;
  }

  public void setRoomType(String roomType) {
    this.roomType = roomType;
  }

  public List<String> getFeatures() {
    return features;
  }

  public void setFeatures(List<String> features) {
    this.features = features == null ? new ArrayList<>() : new ArrayList<>(features);
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
