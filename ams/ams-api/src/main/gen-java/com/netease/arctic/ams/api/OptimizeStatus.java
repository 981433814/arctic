/**
 * Autogenerated by Thrift Compiler (0.13.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.netease.arctic.ams.api;


@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.13.0)", date = "2022-08-22")
public enum OptimizeStatus implements org.apache.thrift.TEnum {
  Init(0),
  Pending(1),
  Executing(2),
  Failed(3),
  Prepared(4),
  Committed(5);

  private final int value;

  private OptimizeStatus(int value) {
    this.value = value;
  }

  /**
   * Get the integer value of this enum value, as defined in the Thrift IDL.
   */
  public int getValue() {
    return value;
  }

  /**
   * Find a the enum type by its integer value, as defined in the Thrift IDL.
   * @return null if the value is not found.
   */
  @org.apache.thrift.annotation.Nullable
  public static OptimizeStatus findByValue(int value) { 
    switch (value) {
      case 0:
        return Init;
      case 1:
        return Pending;
      case 2:
        return Executing;
      case 3:
        return Failed;
      case 4:
        return Prepared;
      case 5:
        return Committed;
      default:
        return null;
    }
  }
}
