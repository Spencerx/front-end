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
package org.opencypher.v9_0.parser

import org.opencypher.v9_0.ast
import org.opencypher.v9_0.ast.CatalogDDL
import org.parboiled.scala._

trait Statement extends Parser
  with Query
  with Command
  with Base {

  def Statement: Rule1[ast.Statement] = rule(
    CatalogCommand
      | Command
      | Query
  )

  def CatalogCommand: Rule1[CatalogDDL] = rule("Catalog DDL statement") {
    ShowDatabase | ShowDatabases | CreateDatabase | DropDatabase | StartDatabase | StopDatabase | CreateGraph | DropGraph | CreateView | DropView
  }

  def ShowDatabase = rule("CATALOG SHOW DATABASE") {
    group(optional(keyword("CATALOG")) ~~ keyword("SHOW DATABASE") ~~ DatabaseNameString) ~~>> (ast.ShowDatabase(_))
  }

  def ShowDatabases = rule("CATALOG SHOW DATABASES") {
    group(optional(keyword("CATALOG")) ~~ keyword("SHOW DATABASES")) ~>>> (_=> ast.ShowDatabases())
  }

  def CreateDatabase = rule("CATALOG CREATE DATABASE") {
    group(optional(keyword("CATALOG")) ~~ keyword("CREATE DATABASE") ~~ DatabaseNameString) ~~>> (ast.CreateDatabase(_))
  }

  def DropDatabase = rule("CATALOG DROP DATABASE") {
    group(optional(keyword("CATALOG")) ~~ keyword("DROP DATABASE") ~~ DatabaseNameString) ~~>> (ast.DropDatabase(_))
  }

  def StartDatabase = rule("CATALOG START DATABASE") {
    group(optional(keyword("CATALOG")) ~~ keyword("START DATABASE") ~~ DatabaseNameString) ~~>> (ast.StartDatabase(_))
  }

  def StopDatabase = rule("CATALOG STOP DATABASE") {
    group(optional(keyword("CATALOG")) ~~ keyword("STOP DATABASE") ~~ DatabaseNameString) ~~>> (ast.StopDatabase(_))
  }

  def CreateGraph = rule("CATALOG CREATE GRAPH") {
    group(keyword("CATALOG CREATE GRAPH") ~~ CatalogName ~~ "{" ~~
      RegularQuery ~~
      "}") ~~>> (ast.CreateGraph(_, _))
  }

  def DropGraph = rule("CATALOG DROP GRAPH") {
    group(keyword("CATALOG DROP GRAPH") ~~ CatalogName) ~~>> (ast.DropGraph(_))
  }

  def CreateView = rule("CATALOG CREATE VIEW") {
    group((keyword("CATALOG CREATE VIEW") | keyword("CATALOG CREATE QUERY")) ~~
      CatalogName ~~ optional("(" ~~ zeroOrMore(Parameter, separator = CommaSep) ~~ ")") ~~ "{" ~~
      captureString(RegularQuery) ~~
      "}") ~~>> { case (name, params, (query, string)) => ast.CreateView(name, params.getOrElse(Seq.empty), query, string) }
  }

  def DropView = rule("CATALOG DROP VIEW") {
    group((keyword("CATALOG DROP VIEW") | keyword("CATALOG DROP QUERY")) ~~ CatalogName) ~~>> (ast.DropView(_))
  }
}
