/*
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
package com.facebook.presto.sql.planner;

import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.expressions.DefaultRowExpressionTraversalVisitor;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.spi.function.FunctionMetadata;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.SpecialFormExpression;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public final class NullabilityAnalyzer
{
    private final FunctionManager functionManager;
    private final TypeManager typeManager;

    public NullabilityAnalyzer(FunctionManager functionManager, TypeManager typeManager)
    {
        this.functionManager = requireNonNull(functionManager, "functionManager is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    public boolean mayReturnNullOnNonNullInput(RowExpression expression)
    {
        requireNonNull(expression, "expression is null");

        AtomicBoolean result = new AtomicBoolean(false);
        expression.accept(new RowExpressionVisitor(functionManager, typeManager), result);
        return result.get();
    }

    private static class RowExpressionVisitor
            extends DefaultRowExpressionTraversalVisitor<AtomicBoolean>
    {
        private final FunctionManager functionManager;
        private final TypeManager typeManager;

        public RowExpressionVisitor(FunctionManager functionManager, TypeManager typeManager)
        {
            this.functionManager = functionManager;
            this.typeManager = typeManager;
        }

        @Override
        public Void visitCall(CallExpression call, AtomicBoolean result)
        {
            FunctionMetadata function = functionManager.getFunctionMetadata(call.getFunctionHandle());
            Optional<OperatorType> operator = function.getOperatorType();
            if (operator.isPresent()) {
                switch (operator.get()) {
                    case SATURATED_FLOOR_CAST:
                    case CAST: {
                        checkArgument(call.getArguments().size() == 1);
                        Type sourceType = call.getArguments().get(0).getType();
                        Type targetType = call.getType();
                        if (!typeManager.isTypeOnlyCoercion(sourceType, targetType)) {
                            result.set(true);
                        }
                        break;
                    }
                    case SUBSCRIPT:
                        result.set(true);
                        break;
                    default:
                        // no-op
                }
            }
            else if (!functionReturnsNullForNotNullInput(function)) {
                // TODO: use function annotation instead of assume all function can return NULL
                result.set(true);
            }
            call.getArguments().forEach(argument -> argument.accept(this, result));
            return null;
        }

        private boolean functionReturnsNullForNotNullInput(FunctionMetadata function)
        {
            return (function.getName().getFunctionName().equalsIgnoreCase("like"));
        }

        @Override
        public Void visitSpecialForm(SpecialFormExpression specialForm, AtomicBoolean result)
        {
            switch (specialForm.getForm()) {
                case IN:
                case IF:
                case SWITCH:
                case WHEN:
                case NULL_IF:
                case DEREFERENCE:
                    result.set(true);
            }
            specialForm.getArguments().forEach(argument -> argument.accept(this, result));
            return null;
        }
    }
}
