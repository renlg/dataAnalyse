package com.dataanalyse.workflow.engine;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {
    @Test void evaluatesContains(){assertTrue(ConditionEvaluator.eval("{{input}} contains '成功'","执行成功",Map.of(),Map.of()));assertFalse(ConditionEvaluator.eval("{{input}} not contains '失败'","执行失败",Map.of(),Map.of()));}
    @Test void evaluatesEquality(){assertTrue(ConditionEvaluator.eval("{{input}} == '完成'","完成",Map.of(),Map.of()));assertTrue(ConditionEvaluator.eval("{{input}} != '失败'","完成",Map.of(),Map.of()));}
    @Test void evaluatesNumericComparison(){assertTrue(ConditionEvaluator.eval("{{input}} > 10",11,Map.of(),Map.of()));assertFalse(ConditionEvaluator.eval("{{input}} > 10",9,Map.of(),Map.of()));}
    @Test void evaluatesAnd(){assertTrue(ConditionEvaluator.eval("{{input}} >= 10 and {{input}} <= 20",15,Map.of(),Map.of()));assertFalse(ConditionEvaluator.eval("{{input}} >= 10 and {{input}} <= 20",21,Map.of(),Map.of()));}
    @Test void givesAndHigherPriorityThanOr(){assertTrue(ConditionEvaluator.eval("1 == 1 or 1 == 2 and 1 == 2",null,Map.of(),Map.of()));}
    @Test void resolvesNodeReference(){assertTrue(ConditionEvaluator.eval("${查询结果} > 3",null,Map.of("查询结果",8),Map.of()));}
    @Test void treatsNullAndEmptyStringAsEqual(){assertTrue(ConditionEvaluator.eval("{{input}} == ''",null,Map.of(),Map.of()));}
}
