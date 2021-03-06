package com.prisma.api.connector.jdbc.database

import com.prisma.api.connector._
import com.prisma.gc_values.NullGCValue
import com.prisma.shared.models.{RelationField, ScalarField}
import org.jooq.Condition
import org.jooq.impl.DSL._

trait FilterConditionBuilder extends BuilderBase {
  def buildConditionForFilter(filter: Option[Filter]): Condition = filter match {
    case Some(filter) => buildConditionForFilter(filter, topLevelAlias)
    case None         => noCondition()
  }

  private def buildConditionForFilter(filter: Filter, alias: String): Condition = {
    def fieldFrom(scalarField: ScalarField) = field(name(alias, scalarField.dbName))
    def nonEmptyConditions(filters: Vector[Filter]): Vector[Condition] = {
      filters.map(buildConditionForFilter(_, alias)) match {
        case x if x.isEmpty => Vector(noCondition())
        case x              => x
      }
    }

    filter match {
      //-------------------------------RECURSION------------------------------------
      case NodeSubscriptionFilter() => and(trueCondition())
      case AndFilter(filters)       => nonEmptyConditions(filters).reduceLeft(_ and _)
      case OrFilter(filters)        => nonEmptyConditions(filters).reduceLeft(_ or _)
      case NotFilter(filters)       => filters.map(buildConditionForFilter(_, alias)).foldLeft(and(trueCondition()))(_ andNot _)
      case NodeFilter(filters)      => buildConditionForFilter(OrFilter(filters), alias)
      case x: RelationFilter        => relationFilterStatement(alias, x)
      //--------------------------------ANCHORS------------------------------------
      case TrueFilter                                            => trueCondition()
      case FalseFilter                                           => falseCondition()
      case ScalarFilter(scalarField, Contains(_))                => fieldFrom(scalarField).contains(stringDummy)
      case ScalarFilter(scalarField, NotContains(_))             => fieldFrom(scalarField).notContains(stringDummy)
      case ScalarFilter(scalarField, StartsWith(_))              => fieldFrom(scalarField).startsWith(stringDummy)
      case ScalarFilter(scalarField, NotStartsWith(_))           => fieldFrom(scalarField).startsWith(stringDummy).not()
      case ScalarFilter(scalarField, EndsWith(_))                => fieldFrom(scalarField).endsWith(stringDummy)
      case ScalarFilter(scalarField, NotEndsWith(_))             => fieldFrom(scalarField).endsWith(stringDummy).not()
      case ScalarFilter(scalarField, LessThan(_))                => fieldFrom(scalarField).lessThan(stringDummy)
      case ScalarFilter(scalarField, GreaterThan(_))             => fieldFrom(scalarField).greaterThan(stringDummy)
      case ScalarFilter(scalarField, LessThanOrEquals(_))        => fieldFrom(scalarField).lessOrEqual(stringDummy)
      case ScalarFilter(scalarField, GreaterThanOrEquals(_))     => fieldFrom(scalarField).greaterOrEqual(stringDummy)
      case ScalarFilter(scalarField, NotEquals(NullGCValue))     => fieldFrom(scalarField).isNotNull
      case ScalarFilter(scalarField, NotEquals(_))               => fieldFrom(scalarField).notEqual(stringDummy)
      case ScalarFilter(scalarField, Equals(NullGCValue))        => fieldFrom(scalarField).isNull
      case ScalarFilter(scalarField, Equals(x))                  => fieldFrom(scalarField).equal(stringDummy)
      case ScalarFilter(scalarField, In(Vector(NullGCValue)))    => fieldFrom(scalarField).isNull
      case ScalarFilter(scalarField, NotIn(Vector(NullGCValue))) => fieldFrom(scalarField).isNotNull
      case ScalarFilter(scalarField, In(values))                 => fieldFrom(scalarField).in(Vector.fill(values.length) { stringDummy }: _*)
      case ScalarFilter(scalarField, NotIn(values))              => fieldFrom(scalarField).notIn(Vector.fill(values.length) { stringDummy }: _*)
      case OneRelationIsNullFilter(field)                        => oneRelationIsNullFilter(field, alias)
      case x                                                     => sys.error(s"Not supported: $x")
    }
  }

  private def relationFilterStatement(alias: String, relationFilter: RelationFilter): Condition = {
    val relationField         = relationFilter.field
    val relation              = relationField.relation
    val newAlias              = relationField.relatedModel_!.dbName + "_" + alias
    val nestedFilterStatement = buildConditionForFilter(relationFilter.nestedFilter, newAlias)

    val select = sql
      .select()
      .from(modelTable(relationField.relatedModel_!).as(newAlias))
      .innerJoin(relationTable(relation))
      .on(modelIdColumn(newAlias, relationField.relatedModel_!).eq(relationColumn(relation, relationField.oppositeRelationSide)))
      .where(relationColumn(relation, relationField.relationSide).eq(modelIdColumn(alias, relationField.model)))

    relationFilter.condition match {
      case AtLeastOneRelatedNode => exists(select.and(nestedFilterStatement))
      case EveryRelatedNode      => notExists(select.andNot(nestedFilterStatement))
      case NoRelatedNode         => notExists(select.and(nestedFilterStatement))
      case NoRelationCondition   => exists(select.and(nestedFilterStatement))
    }
  }

  private def oneRelationIsNullFilter(relationField: RelationField, alias: String): Condition = {
    val relation = relationField.relation
    val select = sql
      .select()
      .from(relationTable(relation))
      .where(relationColumn(relation, relationField.relationSide).eq(modelIdColumn(alias, relationField.relatedModel_!)))

    notExists(select)
  }
}
