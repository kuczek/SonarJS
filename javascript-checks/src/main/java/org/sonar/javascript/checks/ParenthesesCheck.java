/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011 SonarSource and Eriks Nukis
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.javascript.checks;

import com.google.common.collect.ImmutableSet;
import com.sonar.sslr.api.AstNode;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.javascript.api.EcmaScriptKeyword;
import org.sonar.javascript.model.interfaces.Tree;
import org.sonar.javascript.model.interfaces.Tree.Kind;
import org.sonar.javascript.model.interfaces.expression.BinaryExpressionTree;
import org.sonar.javascript.model.interfaces.expression.UnaryExpressionTree;
import org.sonar.javascript.parser.EcmaScriptGrammar;
import org.sonar.squidbridge.checks.SquidCheck;
import org.sonar.sslr.parser.LexerlessGrammar;

import java.util.Set;

/**
 * http://google-styleguide.googlecode.com/svn/trunk/javascriptguide.xml?showone=Parentheses#Parentheses
 *
 * @author Eriks Nukis
 */
@Rule(
  key = "Parentheses",
  priority = Priority.MINOR)
public class ParenthesesCheck extends SquidCheck<LexerlessGrammar> {

  @Override
  public void init() {
    subscribeTo(
      Kind.DELETE,
      Kind.TYPEOF,
      Kind.VOID,
      EcmaScriptKeyword.RETURN,
      EcmaScriptKeyword.THROW,
      Kind.NEW_EXPRESSION,
      EcmaScriptKeyword.IN);
  }

  @Override
  public void visitNode(AstNode node) {
    if (node.is(Kind.DELETE, Kind.TYPEOF, Kind.VOID)) {
      UnaryExpressionTree prefixExpr = (UnaryExpressionTree) node;

      if (startsWithOpenParenthesis(((AstNode) prefixExpr.expression()))) {
        reportIssue(node);
      }

    } else if (node.is(Kind.NEW_EXPRESSION)) {
      AstNode expression = node.getFirstChild(EcmaScriptKeyword.NEW).getNextAstNode().getFirstChild();

      if (!node.hasDirectChildren(Kind.ARGUMENTS) && expression.is(Kind.PARENTHESISED_EXPRESSION) && isBinaryExpression(expression.getFirstChild(EcmaScriptGrammar.EXPRESSION))) {
        reportIssue(node);
      }

    } else if (isNotRelationalInExpression(node) && startsWithOpenParenthesis(node.getNextAstNode())) {
      reportIssue(node);
    }
  }

  private boolean isBinaryExpression(AstNode expression) {
    return !(expression.getFirstChild() instanceof BinaryExpressionTree);
  }

  private boolean isNotRelationalInExpression(AstNode node) {
    return !(node.is(EcmaScriptKeyword.IN) && node.getParent().is(Kind.RELATIONAL_IN));
  }

  private void reportIssue(AstNode node) {
    getContext().createLineViolation(this, "Those parentheses are useless.", node);
  }

  private boolean startsWithOpenParenthesis(AstNode expression) {
    return "(".equals(expression.getTokenValue());
  }

}
