/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

import * as esprima from 'esprima';
import * as estree from 'estree';
import { getRegexpRange } from 'utils';
import * as regexpp from 'regexpp';

it('should get range for regexp /s*', () => {
  const program = esprima.parse(`'/s*'`);
  const literal: estree.Literal = program.body[0].expression;
  const regexNode = regexpp.parseRegExpLiteral(new RegExp(literal.value as string));
  const quantifier = regexNode.pattern.alternatives[0].elements[1]; // s*
  const range = getRegexpRange(literal, quantifier);
  expect(range).toStrictEqual([2, 4]);
});

it('should get range for regexp |/?[a-z]', () => {
  const program = esprima.parse(`'|/?[a-z]'`);
  const literal: estree.Literal = program.body[0].expression;
  const regexNode = regexpp.parseRegExpLiteral(new RegExp(literal.value as string));
  const alternative = regexNode.pattern.alternatives[1]; // /?[a-z]
  const range = getRegexpRange(literal, alternative);
  expect(range).toStrictEqual([2, 9]);
});
