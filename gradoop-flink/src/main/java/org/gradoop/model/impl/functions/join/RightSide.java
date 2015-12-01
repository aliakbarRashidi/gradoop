package org.gradoop.model.impl.functions.join;

import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.java.functions.FunctionAnnotation;

/**
 * left, right => right
 *
 * @param <L> left type
 * @param <R> right type
 */
@FunctionAnnotation.ForwardedFieldsSecond("*->*")
public class RightSide<L, R> implements JoinFunction<L, R, R> {

  @Override
  public R join(L l, R r) throws Exception {
    return r;
  }
}