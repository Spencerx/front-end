/*
 * Copyright © 2002-2019 Neo4j Sweden AB (http://neo4j.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.v9_0.rewriting.rewriters

import org.opencypher.v9_0.ast.{AliasedReturnItem, ProjectionClause}
import org.opencypher.v9_0.expressions
import org.opencypher.v9_0.expressions._
import org.opencypher.v9_0.util.Foldable.FoldableAny
import org.opencypher.v9_0.util.{InternalException, Ref, Rewriter, topDown}

import scala.annotation.tailrec

case object projectNamedPaths extends Rewriter {

  case class Projectibles(paths: Map[Variable, PathExpression] = Map.empty,
                          protectedVariables: Set[Ref[LogicalVariable]] = Set.empty,
                          variableRewrites: Map[Ref[LogicalVariable], PathExpression] = Map.empty) {

    self =>

    def withoutNamedPaths: Projectibles = copy(paths = Map.empty)
    def withProtectedVariable(ident: Ref[LogicalVariable]): Projectibles = copy(protectedVariables = protectedVariables + ident)
    def withNamedPath(entry: (Variable, PathExpression)): Projectibles = copy(paths = paths + entry)
    def withRewrittenVariable(entry: (Ref[LogicalVariable], PathExpression)): Projectibles = {
      val (ref, pathExpr) = entry
      copy(variableRewrites = variableRewrites + (ref -> pathExpr.endoRewrite(copyVariables)))
    }

    def returnItems: IndexedSeq[AliasedReturnItem] = paths.map {
      case (ident, pathExpr) => AliasedReturnItem(pathExpr, ident)(ident.position)
    }.toIndexedSeq

    def withVariableRewritesForExpression(expr: Expression): Projectibles =
      expr.treeFold(self) {
        case ident: Variable =>
          acc =>
            acc.paths.get(ident) match {
              case Some(pathExpr) => (acc.withRewrittenVariable(Ref(ident) -> pathExpr), Some(identity))
              case None => (acc, Some(identity))
            }
      }
  }

  object Projectibles {
    val empty = Projectibles()
  }

  def apply(input: AnyRef): AnyRef = {
    val Projectibles(paths, protectedVariables, variableRewrites) = collectProjectibles(input)
    val applicator = Rewriter.lift {

      case ident: Variable if !protectedVariables(Ref(ident)) =>
        variableRewrites.getOrElse(Ref(ident), ident)

      case namedPart@NamedPatternPart(_, _: ShortestPaths) =>
        namedPart

      case NamedPatternPart(_, part) =>
        part

      case expr: PathExpression =>
        expr
    }
    topDown(applicator)(input)
  }

  private def collectProjectibles(input: AnyRef): Projectibles = input.treeFold(Projectibles.empty) {
    case aliased: AliasedReturnItem =>
      acc =>
        (acc.withProtectedVariable(Ref(aliased.variable)), Some(identity))

    case ident: Variable =>
      acc =>
        acc.paths.get(ident) match {
          case Some(pathExpr) => (acc.withRewrittenVariable(Ref(ident) -> pathExpr), Some(identity))
          case None => (acc, Some(identity))
        }

    // Optimization 1
    //
    // M p = ... WHERE ..., p, p, ...
    // M p = ... WITH a AS a, b AS b, PathExpr AS p WHERE ...

    // Optimization 2
    //
    // plan rewriter for pushing projections up the tree

    // TODO: Project for use in WHERE
    // TODO: Pull out common subexpressions for path expr using WITH *, ... and run expand star again
    // TODO: Plan level rewriting to delay computation of unused projections

    case projection: ProjectionClause =>
      acc =>
        val projectedAcc = projection.returnItems.items.map(_.expression).foldLeft(acc) {
          (acc, expr) => acc.withVariableRewritesForExpression(expr)
        }
        (projectedAcc, Some(_.withoutNamedPaths))

    case NamedPatternPart(_, part: ShortestPaths) =>
      acc => (acc, Some(identity))

    case part @ NamedPatternPart(variable, patternPart) =>
      acc =>
        val pathExpr = expressions.PathExpression(patternPartPathExpression(patternPart))(part.position)
        (acc.withNamedPath(variable -> pathExpr).withProtectedVariable(Ref(variable)), Some(identity))
  }

  def patternPartPathExpression(patternPart: AnonymousPatternPart): PathStep = patternPart match {
    case EveryPath(element) => patternPartPathExpression(element)
    case x                  => throw new InternalException(s"Unknown pattern part: $x")
  }

  def patternPartPathExpression(element: PatternElement): PathStep = flip(element, NilPathStep)

  @tailrec
  private def flip(element: PatternElement, step: PathStep): PathStep  = {
    element match {
      case NodePattern(node, _, _, _) =>
        NodePathStep(node.get.copyId, step)

      case RelationshipChain(relChain, RelationshipPattern(rel, _, length, _, direction, _, _), to) => length match {
        case None =>
          flip(relChain, SingleRelationshipPathStep(rel.get.copyId, direction, to.variable.map(_.copyId), step))

        case Some(_) =>
          flip(relChain, MultiRelationshipPathStep(rel.get.copyId, direction, to.variable.map(_.copyId), step))
      }
    }
  }
}
