/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.ast;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.impl.*;
import org.eclipse.jdt.internal.compiler.codegen.*;
import org.eclipse.jdt.internal.compiler.flow.*;
import org.eclipse.jdt.internal.compiler.lookup.*;

public class IfStatement extends Statement {
	
	//this class represents the case of only one statement in 
	//either else and/or then branches.

	public Expression condition;
	public Statement thenStatement;
	public Statement elseStatement;

	boolean thenExit;

	// for local variables table attributes
	int thenInitStateIndex = -1;
	int elseInitStateIndex = -1;
	int mergedInitStateIndex = -1;

	public IfStatement(Expression condition, Statement thenStatement, 	int sourceStart, int sourceEnd) {

		this.condition = condition;
		this.thenStatement = thenStatement;
		// remember useful empty statement
		if (thenStatement instanceof EmptyStatement) thenStatement.bits |= IsUsefulEmptyStatement;
		this.sourceStart = sourceStart;
		this.sourceEnd = sourceEnd;
	}

	public IfStatement(Expression condition, Statement thenStatement, Statement elseStatement, int sourceStart, int sourceEnd) {

		this.condition = condition;
		this.thenStatement = thenStatement;
		// remember useful empty statement
		if (thenStatement instanceof EmptyStatement) thenStatement.bits |= IsUsefulEmptyStatement;
		this.elseStatement = elseStatement;
		if (elseStatement instanceof IfStatement) elseStatement.bits |= IsElseIfStatement;
		this.sourceStart = sourceStart;
		this.sourceEnd = sourceEnd;
	}

	public FlowInfo analyseCode(
		BlockScope currentScope,
		FlowContext flowContext,
		FlowInfo flowInfo) {

		// process the condition
		flowInfo = condition.analyseCode(currentScope, flowContext, flowInfo);

		Constant cst = this.condition.optimizedBooleanConstant();
		boolean isConditionOptimizedTrue = cst != Constant.NotAConstant && cst.booleanValue() == true;
		boolean isConditionOptimizedFalse = cst != Constant.NotAConstant && cst.booleanValue() == false;
		
		// process the THEN part
		FlowInfo thenFlowInfo = flowInfo.initsWhenTrue().copy();
		if (isConditionOptimizedFalse) {
			thenFlowInfo.setReachMode(FlowInfo.UNREACHABLE); 
		}
		FlowInfo elseFlowInfo = flowInfo.initsWhenFalse().copy();
		if (isConditionOptimizedTrue) {
			elseFlowInfo.setReachMode(FlowInfo.UNREACHABLE); 
		}
		this.condition.checkNullComparison(currentScope, flowContext, flowInfo, thenFlowInfo, elseFlowInfo);
		if (this.thenStatement != null) {
			// Save info for code gen
			thenInitStateIndex =
				currentScope.methodScope().recordInitializationStates(thenFlowInfo);
			if (!thenStatement.complainIfUnreachable(thenFlowInfo, currentScope, false)) {
				thenFlowInfo =
					thenStatement.analyseCode(currentScope, flowContext, thenFlowInfo);
			}
		}
		// code gen: optimizing the jump around the ELSE part
		this.thenExit =  !thenFlowInfo.isReachable();

		// process the ELSE part
		if (this.elseStatement != null) {
		    // signal else clause unnecessarily nested, tolerate else-if code pattern
		    if (thenFlowInfo == FlowInfo.DEAD_END 
		            && (this.bits & IsElseIfStatement) == 0 	// else of an else-if
		            && !(this.elseStatement instanceof IfStatement)) {
		        currentScope.problemReporter().unnecessaryElse(this.elseStatement);
		    }
			// Save info for code gen
			elseInitStateIndex =
				currentScope.methodScope().recordInitializationStates(elseFlowInfo);
			if (!elseStatement.complainIfUnreachable(elseFlowInfo, currentScope, false)) {
				elseFlowInfo =
					elseStatement.analyseCode(currentScope, flowContext, elseFlowInfo);
			}
		}

		// merge THEN & ELSE initializations
		FlowInfo mergedInfo = FlowInfo.mergedOptimizedBranches(
				thenFlowInfo, 
				isConditionOptimizedTrue, 
				elseFlowInfo, 
				isConditionOptimizedFalse,
				true /*if(true){ return; }  fake-reachable(); */);
		mergedInitStateIndex = currentScope.methodScope().recordInitializationStates(mergedInfo);
		return mergedInfo;
	}

	/**
	 * If code generation
	 *
	 * @param currentScope org.eclipse.jdt.internal.compiler.lookup.BlockScope
	 * @param codeStream org.eclipse.jdt.internal.compiler.codegen.CodeStream
	 */
	public void generateCode(BlockScope currentScope, CodeStream codeStream) {

		if ((this.bits & IsReachable) == 0) {
			return;
		}
		int pc = codeStream.position;
		Label endifLabel = new Label(codeStream);

		// optimizing the then/else part code gen
		Constant cst;
		boolean hasThenPart = 
			!(((cst = this.condition.optimizedBooleanConstant()) != Constant.NotAConstant
					&& cst.booleanValue() == false)
				|| this.thenStatement == null
				|| this.thenStatement.isEmptyBlock());
		boolean hasElsePart =
			!((cst != Constant.NotAConstant && cst.booleanValue() == true)
				|| this.elseStatement == null
				|| this.elseStatement.isEmptyBlock());

		if (hasThenPart) {
			Label falseLabel = null;
			// generate boolean condition
			this.condition.generateOptimizedBoolean(
				currentScope,
				codeStream,
				null,
				hasElsePart ? (falseLabel = new Label(codeStream)) : endifLabel,
				true);
			// May loose some local variable initializations : affecting the local variable attributes
			if (thenInitStateIndex != -1) {
				codeStream.removeNotDefinitelyAssignedVariables(currentScope, thenInitStateIndex);
				codeStream.addDefinitelyAssignedVariables(currentScope, thenInitStateIndex);
			}
			// generate then statement
			this.thenStatement.generateCode(currentScope, codeStream);
			// jump around the else statement
			if (hasElsePart) {
				if (!thenExit) {
					this.thenStatement.branchChainTo(endifLabel);
					int position = codeStream.position;
					codeStream.goto_(endifLabel);
					//goto is tagged as part of the thenAction block
					codeStream.updateLastRecordedEndPC((this.thenStatement instanceof Block) ? ((Block) this.thenStatement).scope : currentScope, position);
					// generate else statement
				}
				// May loose some local variable initializations : affecting the local variable attributes
				if (elseInitStateIndex != -1) {
					codeStream.removeNotDefinitelyAssignedVariables(
						currentScope,
						elseInitStateIndex);
					codeStream.addDefinitelyAssignedVariables(currentScope, elseInitStateIndex);
				}
				if (falseLabel != null) falseLabel.place();
				this.elseStatement.generateCode(currentScope, codeStream);
			}
		} else if (hasElsePart) {
			// generate boolean condition
			this.condition.generateOptimizedBoolean(
				currentScope,
				codeStream,
				endifLabel,
				null,
				true);
			// generate else statement
			// May loose some local variable initializations : affecting the local variable attributes
			if (elseInitStateIndex != -1) {
				codeStream.removeNotDefinitelyAssignedVariables(
					currentScope,
					elseInitStateIndex);
				codeStream.addDefinitelyAssignedVariables(currentScope, elseInitStateIndex);
			}
			this.elseStatement.generateCode(currentScope, codeStream);
		} else {
			// generate condition side-effects
			this.condition.generateCode(currentScope, codeStream, false);
			codeStream.recordPositionsFrom(pc, this.sourceStart);
		}
		// May loose some local variable initializations : affecting the local variable attributes
		if (mergedInitStateIndex != -1) {
			codeStream.removeNotDefinitelyAssignedVariables(
				currentScope,
				mergedInitStateIndex);
			codeStream.addDefinitelyAssignedVariables(currentScope, mergedInitStateIndex);
		}
		endifLabel.place();
		codeStream.recordPositionsFrom(pc, this.sourceStart);
	}

	public StringBuffer printStatement(int indent, StringBuffer output) {

		printIndent(indent, output).append("if ("); //$NON-NLS-1$
		condition.printExpression(0, output).append(")\n");	//$NON-NLS-1$ 
		thenStatement.printStatement(indent + 2, output);
		if (elseStatement != null) {
			output.append('\n');
			printIndent(indent, output);
			output.append("else\n"); //$NON-NLS-1$
			elseStatement.printStatement(indent + 2, output);
		}
		return output;
	}

	public void resolve(BlockScope scope) {

		TypeBinding type = condition.resolveTypeExpecting(scope, TypeBinding.BOOLEAN);
		condition.computeConversion(scope, type, type);
		if (thenStatement != null)
			thenStatement.resolve(scope);
		if (elseStatement != null)
			elseStatement.resolve(scope);
	}

	public void traverse(
		ASTVisitor visitor,
		BlockScope blockScope) {

		if (visitor.visit(this, blockScope)) {
			condition.traverse(visitor, blockScope);
			if (thenStatement != null)
				thenStatement.traverse(visitor, blockScope);
			if (elseStatement != null)
				elseStatement.traverse(visitor, blockScope);
		}
		visitor.endVisit(this, blockScope);
	}
}
