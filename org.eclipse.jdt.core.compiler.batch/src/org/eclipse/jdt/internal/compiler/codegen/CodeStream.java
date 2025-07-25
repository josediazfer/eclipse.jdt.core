/*******************************************************************************
 * Copyright (c) 2000, 2025 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann - Contribution for
 *								bug 400710 - [1.8][compiler] synthetic access to default method generates wrong code
 *								bug 391376 - [1.8] check interaction of default methods with bridge methods and generics
 *								bug 421543 - [1.8][compiler] Compiler fails to recognize default method being turned into abstract by subtytpe
 *     Jesper S Moller - Contributions for
 *							Bug 405066 - [1.8][compiler][codegen] Implement code generation infrastructure for JSR335
 *     Andy Clement (GoPivotal, Inc) aclement@gopivotal.com - Contributions for
 *                          Bug 383624 - [1.8][compiler] Revive code generation support for type annotations (from Olivier's work)
 *                          Bug 409247 - [1.8][compiler] Verify error with code allocating multidimensional array
 *                          Bug 409236 - [1.8][compiler] Type annotations on intersection cast types dropped by code generator
 *                          Bug 409250 - [1.8][compiler] Various loose ends in 308 code generation
 *                          Bug 405104 - [1.8][compiler][codegen] Implement support for serializeable lambdas
 *                          Bug 449467 - [1.8][compiler] Invalid lambda deserialization with anonymous class
 *        Olivier Tardieu (tardieu@us.ibm.com) - Contributions for
 *                          Bug 442418 - $deserializeLambda$ off-by-one error when deserializing the captured arguments of a lambda that also capture this
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.codegen.OperandStack.OperandCategory;
import org.eclipse.jdt.internal.compiler.flow.UnconditionalFlowInfo;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.Constant;
import org.eclipse.jdt.internal.compiler.impl.JavaFeature;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.problem.AbortMethod;
import org.eclipse.jdt.internal.compiler.util.Util;

@SuppressWarnings({"rawtypes", "unchecked"})
public class CodeStream {

	// It will be responsible for the following items.
	// -> Tracking Max Stack.

	public static FieldBinding[] ImplicitThis = new FieldBinding[] {};
	public static final int LABELS_INCREMENT = 5;
	// local variable attributes output
	public static final int LOCALS_INCREMENT = 10;
	public static final CompilationResult RESTART_IN_WIDE_MODE = new CompilationResult((char[])null, 0, 0, 0);
	public static final CompilationResult RESTART_CODE_GEN_FOR_UNUSED_LOCALS_MODE = new CompilationResult((char[])null, 0, 0, 0);

	public int allLocalsCounter;
	public byte[] bCodeStream;
	public ClassFile classFile; // The current classfile it is associated to.
	public int classFileOffset;
	public ConstantPool constantPool; // The constant pool used to generate bytecodes that need to store information into the constant pool
	public int countLabels;
	public ExceptionLabel[] exceptionLabels = new ExceptionLabel[LABELS_INCREMENT];
	public int exceptionLabelsCounter;
	public int generateAttributes;
	// store all the labels placed at the current position to be able to optimize
	// a jump to the next bytecode.
	static final int L_UNKNOWN = 0, L_OPTIMIZABLE = 2, L_CANNOT_OPTIMIZE = 4;
	public BranchLabel[] labels = new BranchLabel[LABELS_INCREMENT];
	public int lastEntryPC; // last entry recorded
	public int lastAbruptCompletion; // position of last instruction which abrupts completion: goto/return/athrow/{table/lookup}switch

	public int[] lineSeparatorPositions;
	// line number of the body start and the body end
	public int lineNumberStart;

	public int lineNumberEnd;
	public LocalVariableBinding[] locals = new LocalVariableBinding[LOCALS_INCREMENT];
	public int maxFieldCount;
	public int maxLocals;
	public AbstractMethodDeclaration methodDeclaration;
	public LambdaExpression lambdaExpression;
	public int[] pcToSourceMap = new int[24];
	public int pcToSourceMapSize;
	public int position; // So when first set can be incremented
	public boolean preserveUnusedLocals;

	public int stackDepth; // Use Ints to keep from using extra bc when adding

	public int stackMax; // Use Ints to keep from using extra bc when adding
	public int startingClassFileOffset; // I need to keep the starting point inside the byte array
	// target level to manage different code generation between different target levels
	protected long targetLevel;

	public LocalVariableBinding[] visibleLocals = new LocalVariableBinding[LOCALS_INCREMENT];

	int visibleLocalsCount;

	// to handle goto_w
	public boolean wideMode = false;

	public OperandStack operandStack = null;

	public Map<BlockScope, List<ExceptionLabel>> patternAccessorMap = new HashMap<>();
	public Stack<BlockScope> accessorExceptionTrapScopes = new Stack<>();

public CodeStream(ClassFile givenClassFile) {
	this.targetLevel = givenClassFile.targetJDK;
	this.generateAttributes = givenClassFile.produceAttributes;
	if ((givenClassFile.produceAttributes & ClassFileConstants.ATTR_LINES) != 0) {
		this.lineSeparatorPositions = givenClassFile.referenceBinding.scope.referenceCompilationUnit().compilationResult.getLineSeparatorPositions();
	}
}
/**
 * This methods searches for an existing entry inside the pcToSourceMap table with a pc equals to @pc.
 * If there is an existing entry it returns -1 (no insertion required).
 * Otherwise it returns the index where the entry for the pc has to be inserted.
 * This is based on the fact that the pcToSourceMap table is sorted according to the pc.
 *
 * @param pcToSourceMap the given pcToSourceMap array
 * @param length the given length
 * @param pc the given pc
 * @return int
 */
public static int insertionIndex(int[] pcToSourceMap, int length, int pc) {
	int g = 0;
	int d = length - 2;
	int m = 0;
	while (g <= d) {
		m = (g + d) / 2;
		// we search only on even indexes
		if ((m & 1) != 0) // faster than ((m % 2) != 0)
			m--;
		int currentPC = pcToSourceMap[m];
		if (pc < currentPC) {
			d = m - 2;
		} else
			if (pc > currentPC) {
				g = m + 2;
			} else {
				return -1;
			}
	}
	if (pc < pcToSourceMap[m])
		return m;
	return m + 2;
}
public static final void sort(int[] tab, int lo0, int hi0, int[] result) {
	int lo = lo0;
	int hi = hi0;
	int mid;
	if (hi0 > lo0) {
		/* Arbitrarily establishing partition element as the midpoint of
		  * the array.
		  */
		mid = tab[lo0 + (hi0 - lo0) / 2];
		// loop through the array until indices cross
		while (lo <= hi) {
			/* find the first element that is greater than or equal to
			 * the partition element starting from the left Index.
			 */
			while ((lo < hi0) && (tab[lo] < mid))
				++lo;
			/* find an element that is smaller than or equal to
			 * the partition element starting from the right Index.
			 */
			while ((hi > lo0) && (tab[hi] > mid))
				--hi;
			// if the indexes have not crossed, swap
			if (lo <= hi) {
				swap(tab, lo, hi, result);
				++lo;
				--hi;
			}
		}
		/* If the right index has not reached the left side of array
		  * must now sort the left partition.
		  */
		if (lo0 < hi)
			sort(tab, lo0, hi, result);
		/* If the left index has not reached the right side of array
		  * must now sort the right partition.
		  */
		if (lo < hi0)
			sort(tab, lo, hi0, result);
	}
}


private static final void swap(int a[], int i, int j, int result[]) {
	int T;
	T = a[i];
	a[i] = a[j];
	a[j] = T;
	T = result[j];
	result[j] = result[i];
	result[i] = T;
}

public void aaload() {
	this.countLabels = 0;
	this.stackDepth--;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aaload;
	this.operandStack.xaload();
}

public void aastore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aastore;
	this.operandStack.xastore();
}

public void aconst_null() {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aconst_null;
	this.operandStack.push(TypeBinding.NULL);
}

public void addDefinitelyAssignedVariables(Scope scope, int initStateIndex) {
	// Required to fix 1PR0XVS: LFRE:WINNT - Compiler: variable table for method appears incorrect
	for (int i = 0; i < this.visibleLocalsCount; i++) {
		LocalVariableBinding localBinding = this.visibleLocals[i];
		if (localBinding != null) {
			// Check if the local is definitely assigned
			if (isDefinitelyAssigned(scope, initStateIndex, localBinding)) {
				if ((localBinding.initializationCount == 0) || (localBinding.initializationPCs[((localBinding.initializationCount - 1) << 1) + 1] != -1)) {
					/* There are two cases:
					 * 1) there is no initialization interval opened ==> add an opened interval
					 * 2) there is already some initialization intervals but the last one is closed ==> add an opened interval
					 * An opened interval means that the value at localBinding.initializationPCs[localBinding.initializationCount - 1][1]
					 * is equals to -1.
					 * initializationPCs is a collection of pairs of int:
					 * 	first value is the startPC and second value is the endPC. -1 one for the last value means that the interval
					 * 	is not closed yet.
					 */
					localBinding.recordInitializationStartPC(this.position);
				}
			}
		}
	}
}

public void addLabel(BranchLabel aLabel) {
	if (this.countLabels == this.labels.length)
		System.arraycopy(this.labels, 0, this.labels = new BranchLabel[this.countLabels + LABELS_INCREMENT], 0, this.countLabels);
	this.labels[this.countLabels++] = aLabel;
}

public void addVariable(LocalVariableBinding localBinding) {
	/* do nothing */
}

public void addVisibleLocalVariable(LocalVariableBinding localBinding) {
	if (this.visibleLocalsCount >= this.visibleLocals.length)
		System.arraycopy(this.visibleLocals, 0, this.visibleLocals = new LocalVariableBinding[this.visibleLocalsCount * 2], 0, this.visibleLocalsCount);
	this.visibleLocals[this.visibleLocalsCount++] = localBinding;
}

public void aload(int iArg) {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	this.operandStack.push(iArg);
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload;
		writeUnsignedShort(iArg);
	} else {
		// Don't need to use the wide bytecode
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void aload_0() {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (this.maxLocals == 0) {
		this.maxLocals = 1;
	}
	this.operandStack.push(0);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload_0;
}

public void aload_1() {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	this.operandStack.push(1);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload_1;
}

public void aload_2() {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	this.operandStack.push(2);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload_2;
}

public void aload_3() {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	this.operandStack.push(3);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_aload_3;
}

public void anewarray(TypeBinding typeBinding) {
	this.countLabels = 0;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_anewarray;
	writeUnsignedShort(this.constantPool.literalIndexForType(typeBinding));

	ClassScope scope = this.classFile.referenceBinding.scope;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(scope.createArrayType(typeBinding, 1));
}

public void areturn() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_areturn;
	this.lastAbruptCompletion = this.position;
}

public void arrayAt(int typeBindingID) {
	switch (typeBindingID) {
		case TypeIds.T_int :
			iaload();
			break;
		case TypeIds.T_byte :
		case TypeIds.T_boolean :
			baload();
			break;
		case TypeIds.T_short :
			saload();
			break;
		case TypeIds.T_char :
			caload();
			break;
		case TypeIds.T_long :
			laload();
			break;
		case TypeIds.T_float :
			faload();
			break;
		case TypeIds.T_double :
			daload();
			break;
		default :
			aaload();
	}
}

public void arrayAtPut(int elementTypeID, boolean valueRequired) {
	switch (elementTypeID) {
		case TypeIds.T_int :
			if (valueRequired)
				dup_x2();
			iastore();
			break;
		case TypeIds.T_byte :
		case TypeIds.T_boolean :
			if (valueRequired)
				dup_x2();
			bastore();
			break;
		case TypeIds.T_short :
			if (valueRequired)
				dup_x2();
			sastore();
			break;
		case TypeIds.T_char :
			if (valueRequired)
				dup_x2();
			castore();
			break;
		case TypeIds.T_long :
			if (valueRequired)
				dup2_x2();
			lastore();
			break;
		case TypeIds.T_float :
			if (valueRequired)
				dup_x2();
			fastore();
			break;
		case TypeIds.T_double :
			if (valueRequired)
				dup2_x2();
			dastore();
			break;
		default :
			if (valueRequired)
				dup_x2();
			aastore();
	}
}

public void arraylength() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_arraylength;
	if (!(this.operandStack instanceof OperandStack.NullStack) && !(this.operandStack.pop() instanceof ArrayBinding))
		throw new AssertionError("Unexpected non-array type"); //$NON-NLS-1$
	this.operandStack.push(TypeBinding.INT);
}

public void astore(int iArg) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position+=2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position+=2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void astore_0() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.maxLocals == 0) {
		this.maxLocals = 1;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore_0;
}

public void astore_1() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore_1;
}

public void astore_2() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore_2;
}

public void astore_3() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_astore_3;
}

public void athrow() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_athrow;
	this.lastAbruptCompletion = this.position;
}

public void baload() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.xaload();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_baload;
}

public void bastore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_bastore;
}

public void bipush(byte b) {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 1 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position += 2;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_bipush;
	this.bCodeStream[this.classFileOffset++] = b;
}

public void caload() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.xaload();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_caload;
}

public void castore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_castore;
}

public void checkcast(int baseId) {
	this.countLabels = 0;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_checkcast;
	switch (baseId) {
		case TypeIds.T_byte :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangByteConstantPoolName));
			break;
		case TypeIds.T_short :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangShortConstantPoolName));
			break;
		case TypeIds.T_char :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangCharacterConstantPoolName));
			break;
		case TypeIds.T_int :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangIntegerConstantPoolName));
			break;
		case TypeIds.T_long :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangLongConstantPoolName));
			break;
		case TypeIds.T_float :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangFloatConstantPoolName));
			break;
		case TypeIds.T_double :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangDoubleConstantPoolName));
			break;
		case TypeIds.T_boolean :
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangBooleanConstantPoolName));
	}
	this.operandStack.pop(OperandCategory.ONE);
	this.operandStack.push(this.classFile.referenceBinding.scope.environment().computeBoxingType(TypeBinding.wellKnownBaseType(baseId)));
}

public void checkcast(TypeBinding typeBinding) {
	this.checkcast(null, typeBinding, -1);
}

public void checkcast(TypeReference typeReference, TypeBinding typeBinding, int currentPosition) {
	this.countLabels = 0;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_checkcast;
	writeUnsignedShort(this.constantPool.literalIndexForType(typeBinding));
	this.operandStack.pop(OperandCategory.ONE);
	this.operandStack.push(typeBinding);
}

public void d2f() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_d2f;
}

public void d2i() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_d2i;
}

public void d2l() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_d2l;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.LONG);
}

public void dadd() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dadd;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
}

public void daload() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_daload;
	this.operandStack.xaload();
}

public void dastore() {
	this.countLabels = 0;
	this.stackDepth -= 4;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dastore;
	this.operandStack.xastore();
}

public void dcmpg() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dcmpg;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.INT);
}

public void dcmpl() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dcmpl;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.INT);
}

public void dconst_0() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dconst_0;
}

public void dconst_1() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dconst_1;
}

public void ddiv() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ddiv;
}

public void dload(int iArg) {
	this.countLabels = 0;
	this.stackDepth += 2;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals < iArg + 2) {
		this.maxLocals = iArg + 2; // + 2 because it is a double
	}
	this.operandStack.push(TypeBinding.DOUBLE);
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload;
		writeUnsignedShort(iArg);
	} else {
		// Don't need to use the wide bytecode
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void dload_0() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals < 2) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload_0;
}

public void dload_1() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals < 3) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload_1;
}

public void dload_2() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals < 4) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload_2;
}

public void dload_3() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.maxLocals < 5) {
		this.maxLocals = 5;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dload_3;
}

public void dmul() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dmul;
}

public void dneg() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dneg;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
}

public void drem() {
	this.countLabels = 0;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
	this.stackDepth -= 2;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_drem;
}

public void dreturn() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dreturn;
	this.lastAbruptCompletion = this.position;
}

public void dstore(int iArg) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.maxLocals <= iArg + 1) {
		this.maxLocals = iArg + 2;
	}
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void dstore_0() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.maxLocals < 2) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore_0;
}

public void dstore_1() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.maxLocals < 3) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore_1;
}

public void dstore_2() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.maxLocals < 4) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore_2;
}

public void dstore_3() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	if (this.maxLocals < 5) {
		this.maxLocals = 5;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dstore_3;
}

public void dsub() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.pop(TypeBinding.DOUBLE);
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dsub;
}

public void dup(TypeBinding type) {
	if (TypeIds.getCategory(type.id) == 2)
		dup2();
	else
		dup();
}
public void dup() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(this.operandStack.peek());
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup;
}

public void dup_x1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.dup_x1();
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup_x1;
}

public void dup_x2() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.dup_x2();
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup_x2;
}

public void dup2() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.dup2();
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup2;
}

public void dup2_x1() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.dup2_x1();
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup2_x1;
}

public void dup2_x2() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.dup2_x2();
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_dup2_x2;
}

public void exitUserScope(BlockScope currentScope, Predicate<LocalVariableBinding> condition) {
	// mark all the scope's locals as losing their definite assignment
	int index = this.visibleLocalsCount - 1;
	while (index >= 0) {
		LocalVariableBinding visibleLocal = this.visibleLocals[index];
		if (visibleLocal == null || visibleLocal.declaringScope != currentScope || !condition.test(visibleLocal)) {
			// left currentScope
			index--;
			continue;
		}

		// there may be some preserved locals never initialized
		if (visibleLocal.initializationCount > 0) {
			visibleLocal.recordInitializationEndPC(this.position);
		}
		this.visibleLocals[index--] = null; // this variable is no longer visible afterwards
	}
}

public void exitUserScope(BlockScope currentScope) {
	exitUserScope(currentScope, lvb->true);
}

public void f2d() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_f2d;
}

public void f2i() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_f2i;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.INT);
}

public void f2l() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_f2l;
}

public void fadd() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fadd;
}

public void faload() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.xaload();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_faload;
}

public void fastore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fastore;
}

public void fcmpg() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fcmpg;
}

public void fcmpl() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fcmpl;
}

public void fconst_0() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fconst_0;
}

public void fconst_1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fconst_1;
}

public void fconst_2() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fconst_2;
}

public void fdiv() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fdiv;
}

public void fieldAccess(byte opcode, VariableBinding fieldBinding, TypeBinding declaringClass) {
	if (declaringClass == null) declaringClass = fieldBinding.getDeclaringClass();
	if ((declaringClass.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
		Util.recordNestedType(this.classFile, declaringClass);
	}
	TypeBinding returnType = fieldBinding.type;
	int returnTypeSize;
	switch (returnType.id) {
		case TypeIds.T_long :
		case TypeIds.T_double :
			returnTypeSize = 2;
			break;
		default :
			returnTypeSize = 1;
			break;
	}
	this.fieldAccess(opcode, returnTypeSize, declaringClass.constantPoolName(), fieldBinding.name, returnType.signature(), returnType);
}

private void fieldAccess(byte opcode, int returnTypeSize, char[] declaringClass, char[] fieldName, char[] signature, TypeBinding fieldType) {
	this.countLabels = 0;
	switch(opcode) {
		case Opcodes.OPC_getfield :
			if (returnTypeSize == 2) {
				this.stackDepth++;
			}
			this.operandStack.pop(OperandCategory.ONE);
			this.operandStack.push(fieldType);
			break;
		case Opcodes.OPC_getstatic :
			if (returnTypeSize == 2) {
				this.stackDepth += 2;
			} else {
				this.stackDepth++;
			}
			this.operandStack.push(fieldType);
			break;
		case Opcodes.OPC_putfield :
			if (returnTypeSize == 2) {
				this.stackDepth -= 3;
			} else {
				this.stackDepth -= 2;
			}
			this.operandStack.pop();
			this.operandStack.pop(OperandCategory.ONE);
			break;
		case Opcodes.OPC_putstatic :
			if (returnTypeSize == 2) {
				this.stackDepth -= 2;
			} else {
				this.stackDepth--;
			}
			this.operandStack.pop();
	}
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = opcode;
	writeUnsignedShort(this.constantPool.literalIndexForField(declaringClass, fieldName, signature));
}

public void fload(int iArg) {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void fload_0() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.maxLocals == 0) {
		this.maxLocals = 1;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload_0;
}

public void fload_1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload_1;
}

public void fload_2() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload_2;
}

public void fload_3() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fload_3;
}

public void fmul() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fmul;
}

public void fneg() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fneg;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
}

public void frem() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_frem;
}

public void freturn() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_freturn;
	this.lastAbruptCompletion = this.position;
}

public void fstore(int iArg) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void fstore_0() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.maxLocals == 0) {
		this.maxLocals = 1;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore_0;
}

public void fstore_1() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore_1;
}

public void fstore_2() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore_2;
}

public void fstore_3() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fstore_3;
}

public void fsub() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.pop(TypeBinding.FLOAT);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_fsub;
}

public void generateBoxingConversion(int unboxedTypeID) {
	TypeBinding boxedType = this.classFile.referenceBinding.scope.environment().computeBoxingType(TypeBinding.wellKnownBaseType(unboxedTypeID));
    switch (unboxedTypeID) {
        case TypeIds.T_byte :
            // invokestatic: Byte.valueOf(byte)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangByteConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.byteByteSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_short :
            // invokestatic: Short.valueOf(short)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangShortConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.shortShortSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_char :
            // invokestatic: Character.valueOf(char)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangCharacterConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.charCharacterSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_int :
            // invokestatic: Integer.valueOf(int)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangIntegerConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.IntIntegerSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_long :
            // invokestatic: Long.valueOf(long)
			invoke(
			    Opcodes.OPC_invokestatic,
			    2, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangLongConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.longLongSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_float :
            // invokestatic: Float.valueOf(float)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangFloatConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.floatFloatSignature,
			    unboxedTypeID,
			    boxedType);
            break;
        case TypeIds.T_double :
            // invokestatic: Double.valueOf(double)
			invoke(
			    Opcodes.OPC_invokestatic,
			    2, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangDoubleConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.doubleDoubleSignature,
			    unboxedTypeID,
			    boxedType);

            break;
        case TypeIds.T_boolean :
            // invokestatic: Boolean.valueOf(boolean)
			invoke(
			    Opcodes.OPC_invokestatic,
			    1, // receiverAndArgsSize
			    1, // return type size
			    ConstantPool.JavaLangBooleanConstantPoolName,
			    ConstantPool.ValueOf,
			    ConstantPool.booleanBooleanSignature,
			    unboxedTypeID,
			    boxedType);
    }
}

/**
 * Macro for building a class descriptor object
 */
public void generateClassLiteralAccessForType(Scope scope, TypeBinding accessedType) {
	if (accessedType.isBaseType() && accessedType != TypeBinding.NULL) {
		getClass(accessedType);
		return;
	}
	this.ldc(accessedType);
}

/**
 * This method generates the code attribute bytecode
 */
final public void generateCodeAttributeForProblemMethod(String problemMessage) {
	newJavaLangError();
	dup();
	ldc(problemMessage);
	invokeJavaLangErrorConstructor();
	athrow();
}

public void generateConstant(Constant constant, int implicitConversionCode) {
	int targetTypeID = (implicitConversionCode & TypeIds.IMPLICIT_CONVERSION_MASK) >> 4;
	if (targetTypeID == 0) targetTypeID = constant.typeID(); // use default constant type
	switch (targetTypeID) {
		case TypeIds.T_boolean :
			generateInlinedValue(constant.booleanValue());
			break;
		case TypeIds.T_char :
			generateInlinedValue(constant.charValue());
			break;
		case TypeIds.T_byte :
			generateInlinedValue(constant.byteValue());
			break;
		case TypeIds.T_short :
			generateInlinedValue(constant.shortValue());
			break;
		case TypeIds.T_int :
			generateInlinedValue(constant.intValue());
			break;
		case TypeIds.T_long :
			generateInlinedValue(constant.longValue());
			break;
		case TypeIds.T_float :
			generateInlinedValue(constant.floatValue());
			break;
		case TypeIds.T_double :
			generateInlinedValue(constant.doubleValue());
			break;
		case TypeIds.T_JavaLangString :
			ldc(constant.stringValue());
	}
	if ((implicitConversionCode & TypeIds.BOXING) != 0) {
		// need boxing
		generateBoxingConversion(targetTypeID);
	}
}

public void generateEmulatedReadAccessForField(FieldBinding fieldBinding) {
	generateEmulationForField(fieldBinding);
	// swap  the field with the receiver
	this.swap();
	invokeJavaLangReflectFieldGetter(fieldBinding.type);
	if (!fieldBinding.type.isBaseType()) {
		this.checkcast(fieldBinding.type);
	}
}

public void generateEmulatedWriteAccessForField(FieldBinding fieldBinding) {
	invokeJavaLangReflectFieldSetter(fieldBinding.type);
}

public void generateEmulationForConstructor(Scope scope, MethodBinding methodBinding) {
	// leave a java.lang.reflect.Field object on the stack
	this.ldc(String.valueOf(methodBinding.declaringClass.constantPoolName()).replace('/', '.'));
	invokeClassForName();
	int paramLength = methodBinding.parameters.length;
	this.generateInlinedValue(paramLength);
	newArray(scope.createArrayType(scope.getType(TypeConstants.JAVA_LANG_CLASS, 3), 1));
	if (paramLength > 0) {
		dup();
		for (int i = 0; i < paramLength; i++) {
			this.generateInlinedValue(i);
			TypeBinding parameter = methodBinding.parameters[i];
			if (parameter.isBaseType()) {
				getClass(parameter);
			} else if (parameter.isArrayType()) {
				ArrayBinding array = (ArrayBinding)parameter;
				if (array.leafComponentType.isBaseType()) {
					getClass(array.leafComponentType);
				} else {
					this.ldc(String.valueOf(array.leafComponentType.constantPoolName()).replace('/', '.'));
					invokeClassForName();
				}
				int dimensions = array.dimensions;
				this.generateInlinedValue(dimensions);
				newarray(TypeIds.T_int);
				invokeArrayNewInstance();
				invokeObjectGetClass();
			} else {
				// parameter is a reference binding
				this.ldc(String.valueOf(methodBinding.declaringClass.constantPoolName()).replace('/', '.'));
				invokeClassForName();
			}
			aastore();
			if (i < paramLength - 1) {
				dup();
			}
		}
	}
	invokeClassGetDeclaredConstructor();
	dup();
	iconst_1();
	invokeAccessibleObjectSetAccessible();
}

public void generateEmulationForField(FieldBinding fieldBinding) {
	// leave a java.lang.reflect.Field object on the stack
	this.ldc(String.valueOf(fieldBinding.declaringClass.constantPoolName()).replace('/', '.'));
	invokeClassForName();
	this.ldc(String.valueOf(fieldBinding.name));
	invokeClassGetDeclaredField();
	dup();
	iconst_1();
	invokeAccessibleObjectSetAccessible();
}

public void generateEmulationForMethod(Scope scope, MethodBinding methodBinding) {
	// leave a java.lang.reflect.Field object on the stack
	this.ldc(String.valueOf(methodBinding.declaringClass.constantPoolName()).replace('/', '.'));
	invokeClassForName();
	this.ldc(String.valueOf(methodBinding.selector));
	int paramLength = methodBinding.parameters.length;
	this.generateInlinedValue(paramLength);
	newArray(scope.createArrayType(scope.getType(TypeConstants.JAVA_LANG_CLASS, 3), 1));
	if (paramLength > 0) {
		dup();
		for (int i = 0; i < paramLength; i++) {
			this.generateInlinedValue(i);
			TypeBinding parameter = methodBinding.parameters[i];
			if (parameter.isBaseType()) {
				getClass(parameter);
			} else if (parameter.isArrayType()) {
				ArrayBinding array = (ArrayBinding)parameter;
				if (array.leafComponentType.isBaseType()) {
					getClass(array.leafComponentType);
				} else {
					this.ldc(String.valueOf(array.leafComponentType.constantPoolName()).replace('/', '.'));
					invokeClassForName();
				}
				int dimensions = array.dimensions;
				this.generateInlinedValue(dimensions);
				newarray(TypeIds.T_int);
				invokeArrayNewInstance();
				invokeObjectGetClass();
			} else {
				// parameter is a reference binding
				this.ldc(String.valueOf(methodBinding.declaringClass.constantPoolName()).replace('/', '.'));
				invokeClassForName();
			}
			aastore();
			if (i < paramLength - 1) {
				dup();
			}
		}
	}
	invokeClassGetDeclaredMethod();
	dup();
	iconst_1();
	invokeAccessibleObjectSetAccessible();
}
/**
 * Generates the sequence of instructions which will perform the conversion of the expression
 * on the stack into a different type (e.g. long l = someInt; --> i2l must be inserted).
 * @param implicitConversionCode int
 */
public void generateImplicitConversion(int implicitConversionCode) {
	if ((implicitConversionCode & TypeIds.UNBOXING) != 0) {
		final int typeId = implicitConversionCode & TypeIds.COMPILE_TYPE_MASK;
		generateUnboxingConversion(typeId);
		// unboxing can further involve base type conversions
	}
	switch (implicitConversionCode & TypeIds.IMPLICIT_CONVERSION_MASK) {
		case TypeIds.Float2Char :
			f2i();
			i2c();
			break;
		case TypeIds.Double2Char :
			d2i();
			i2c();
			break;
		case TypeIds.Int2Char :
		case TypeIds.Short2Char :
		case TypeIds.Byte2Char :
			i2c();
			break;
		case TypeIds.Long2Char :
			l2i();
			i2c();
			break;
		case TypeIds.Char2Float :
		case TypeIds.Short2Float :
		case TypeIds.Int2Float :
		case TypeIds.Byte2Float :
			i2f();
			break;
		case TypeIds.Double2Float :
			d2f();
			break;
		case TypeIds.Long2Float :
			l2f();
			break;
		case TypeIds.Float2Byte :
			f2i();
			i2b();
			break;
		case TypeIds.Double2Byte :
			d2i();
			i2b();
			break;
		case TypeIds.Int2Byte :
		case TypeIds.Short2Byte :
		case TypeIds.Char2Byte :
			i2b();
			break;
		case TypeIds.Long2Byte :
			l2i();
			i2b();
			break;
		case TypeIds.Byte2Double :
		case TypeIds.Char2Double :
		case TypeIds.Short2Double :
		case TypeIds.Int2Double :
			i2d();
			break;
		case TypeIds.Float2Double :
			f2d();
			break;
		case TypeIds.Long2Double :
			l2d();
			break;
		case TypeIds.Byte2Short :
		case TypeIds.Char2Short :
		case TypeIds.Int2Short :
			i2s();
			break;
		case TypeIds.Double2Short :
			d2i();
			i2s();
			break;
		case TypeIds.Long2Short :
			l2i();
			i2s();
			break;
		case TypeIds.Float2Short :
			f2i();
			i2s();
			break;
		case TypeIds.Double2Int :
			d2i();
			break;
		case TypeIds.Float2Int :
			f2i();
			break;
		case TypeIds.Long2Int :
			l2i();
			break;
		case TypeIds.Int2Long :
		case TypeIds.Char2Long :
		case TypeIds.Byte2Long :
		case TypeIds.Short2Long :
			i2l();
			break;
		case TypeIds.Double2Long :
			d2l();
			break;
		case TypeIds.Float2Long :
			f2l();
			break;
		case TypeIds.Object2boolean:
		case TypeIds.Object2byte:
		case TypeIds.Object2short:
		case TypeIds.Object2int:
		case TypeIds.Object2long:
		case TypeIds.Object2float:
		case TypeIds.Object2char:
		case TypeIds.Object2double:
			// see table 5.1 in JLS S5.5
			// an Object to x conversion should have a check cast
			// and an unboxing conversion.
			int runtimeType = (implicitConversionCode & TypeIds.IMPLICIT_CONVERSION_MASK) >> 4;
			checkcast(runtimeType);
			generateUnboxingConversion(runtimeType);
			break;
	}
	if ((implicitConversionCode & TypeIds.BOXING) != 0) {
		// need to unbox/box the constant
		final int typeId = (implicitConversionCode & TypeIds.IMPLICIT_CONVERSION_MASK) >> 4;
		generateBoxingConversion(typeId);
	}
}

public void generateInlinedValue(boolean inlinedValue) {
	if (inlinedValue)
		iconst_1();
	else
		iconst_0();
}

public void generateInlinedValue(byte inlinedValue) {
	switch (inlinedValue) {
		case -1 :
			iconst_m1();
			break;
		case 0 :
			iconst_0();
			break;
		case 1 :
			iconst_1();
			break;
		case 2 :
			iconst_2();
			break;
		case 3 :
			iconst_3();
			break;
		case 4 :
			iconst_4();
			break;
		case 5 :
			iconst_5();
			break;
		default :
			if ((-128 <= inlinedValue) && (inlinedValue <= 127)) {
				bipush(inlinedValue);
				return;
			}
	}
}

public void generateInlinedValue(char inlinedValue) {
	switch (inlinedValue) {
		case 0 :
			iconst_0();
			break;
		case 1 :
			iconst_1();
			break;
		case 2 :
			iconst_2();
			break;
		case 3 :
			iconst_3();
			break;
		case 4 :
			iconst_4();
			break;
		case 5 :
			iconst_5();
			break;
		default :
			if ((6 <= inlinedValue) && (inlinedValue <= 127)) {
				bipush((byte) inlinedValue);
				return;
			}
			if ((128 <= inlinedValue) && (inlinedValue <= 32767)) {
				sipush(inlinedValue);
				return;
			}
			this.ldc(inlinedValue);
	}
}

public void generateInlinedValue(double inlinedValue) {
	if (inlinedValue == 0.0) {
		if (Double.doubleToLongBits(inlinedValue) != 0L)
			this.ldc2_w(inlinedValue);
		else
			dconst_0();
		return;
	}
	if (inlinedValue == 1.0) {
		dconst_1();
		return;
	}
	this.ldc2_w(inlinedValue);
}

public void generateInlinedValue(float inlinedValue) {
	if (inlinedValue == 0.0f) {
		if (Float.floatToIntBits(inlinedValue) != 0)
			this.ldc(inlinedValue);
		else
			fconst_0();
		return;
	}
	if (inlinedValue == 1.0f) {
		fconst_1();
		return;
	}
	if (inlinedValue == 2.0f) {
		fconst_2();
		return;
	}
	this.ldc(inlinedValue);
}

public void generateInlinedValue(int inlinedValue) {
	switch (inlinedValue) {
		case -1 :
			iconst_m1();
			break;
		case 0 :
			iconst_0();
			break;
		case 1 :
			iconst_1();
			break;
		case 2 :
			iconst_2();
			break;
		case 3 :
			iconst_3();
			break;
		case 4 :
			iconst_4();
			break;
		case 5 :
			iconst_5();
			break;
		default :
			if ((-128 <= inlinedValue) && (inlinedValue <= 127)) {
				bipush((byte) inlinedValue);
				return;
			}
			if ((-32768 <= inlinedValue) && (inlinedValue <= 32767)) {
				sipush(inlinedValue);
				return;
			}
			this.ldc(inlinedValue);
	}
}

public void generateInlinedValue(long inlinedValue) {
	if (inlinedValue == 0) {
		lconst_0();
		return;
	}
	if (inlinedValue == 1) {
		lconst_1();
		return;
	}
	this.ldc2_w(inlinedValue);
}

public void generateInlinedValue(short inlinedValue) {
	switch (inlinedValue) {
		case -1 :
			iconst_m1();
			break;
		case 0 :
			iconst_0();
			break;
		case 1 :
			iconst_1();
			break;
		case 2 :
			iconst_2();
			break;
		case 3 :
			iconst_3();
			break;
		case 4 :
			iconst_4();
			break;
		case 5 :
			iconst_5();
			break;
		default :
			if ((-128 <= inlinedValue) && (inlinedValue <= 127)) {
				bipush((byte) inlinedValue);
				return;
			}
			sipush(inlinedValue);
	}
}

public void generateOuterAccess(Object[] mappingSequence, ASTNode invocationSite, Binding target, Scope scope) {
	if (mappingSequence == null) {
		if (target instanceof LocalVariableBinding) {
			scope.problemReporter().needImplementation(invocationSite); //TODO (philippe) should improve local emulation failure reporting
		} else {
			scope.problemReporter().noSuchEnclosingInstance((ReferenceBinding)target, invocationSite, false);
		}
		return;
	}
	if (mappingSequence == BlockScope.NoEnclosingInstanceInConstructorCall) {
		scope.problemReporter().noSuchEnclosingInstance((ReferenceBinding)target, invocationSite, true);
		return;
	} else if (mappingSequence == BlockScope.NoEnclosingInstanceInStaticContext) {
		scope.problemReporter().noSuchEnclosingInstance((ReferenceBinding)target, invocationSite, false);
		return;
	}

	if (mappingSequence == BlockScope.EmulationPathToImplicitThis) {
		aload_0();
		return;
	} else if (mappingSequence[0] instanceof FieldBinding) {
		FieldBinding fieldBinding = (FieldBinding) mappingSequence[0];
		aload_0();
		fieldAccess(Opcodes.OPC_getfield, fieldBinding, null /* default declaringClass */);
	} else {
		LocalVariableBinding localBinding = (LocalVariableBinding) mappingSequence[0];
		if (localBinding instanceof SyntheticArgumentBinding synth && synth.accessingScope != null) {
			if (!isOuterLocalInInstanceScope(scope, synth.accessingScope)) {
				if (invocationSite instanceof AllocationExpression alloc
						&& alloc.resolvedType instanceof LocalTypeBinding localType && !localType.isRecord()) {
					scope.problemReporter().allocationInStaticContext(invocationSite, localType);
					return;
				} else {
					scope.problemReporter().needImplementation(invocationSite); //TODO improve reporting if this is ever triggered
					return;
				}
			}
		}
		load(localBinding);
	}
	for (int i = 1, length = mappingSequence.length; i < length; i++) {
		if (mappingSequence[i] instanceof FieldBinding) {
			FieldBinding fieldBinding = (FieldBinding) mappingSequence[i];
			fieldAccess(Opcodes.OPC_getfield, fieldBinding, null /* default declaringClass */);
		} else {
			invoke(Opcodes.OPC_invokestatic, (MethodBinding) mappingSequence[i], null /* default declaringClass */);
		}
	}
}

private boolean isOuterLocalInInstanceScope(Scope scope, Scope accessingScope) {
	Scope current = scope;
	while (current != null) {
		if (current == accessingScope)
			return true;
		if (current instanceof MethodScope ms && !ms.isConstructorCall && ms.isStatic) {
			return false;
		} else if (current instanceof ClassScope cs) {
			SourceTypeBinding binding = cs.referenceContext.binding;
			if (binding.superclass instanceof LocalTypeBinding superLocal) {
				if (isOuterLocalInInstanceScope(superLocal.scope, accessingScope))
					return true;
			}
		}
		current = current.parent;
	}
	return false;
}

public void generateReturnBytecode(Expression expression) {
	if (expression == null) {
		return_();
	} else {
		final int implicitConversion = expression.implicitConversion;
		if ((implicitConversion & TypeIds.BOXING) != 0) {
			areturn();
			return;
		}
		int runtimeType = (implicitConversion & TypeIds.IMPLICIT_CONVERSION_MASK) >> 4;
		switch (runtimeType) {
			case TypeIds.T_boolean :
			case TypeIds.T_int :
				ireturn();
				break;
			case TypeIds.T_float :
				freturn();
				break;
			case TypeIds.T_long :
				lreturn();
				break;
			case TypeIds.T_double :
				dreturn();
				break;
			default :
				areturn();
		}
	}
}
public void invokeDynamicForStringConcat(StringBuilder recipe, List<TypeBinding> arguments) {
	int invokeDynamicNumber = this.classFile.recordBootstrapMethod(recipe.toString());
	StringBuilder signature = new StringBuilder("("); //$NON-NLS-1$
	int argsSize = 0;
	for (TypeBinding argument : arguments) {
		signature.append(argument.signature());
		argsSize += TypeIds.getCategory(argument.id);
	}
	signature.append(")Ljava/lang/String;"); //$NON-NLS-1$
	this.invokeDynamic(invokeDynamicNumber,
			argsSize,
			1, // Ljava/lang/String;
			ConstantPool.ConcatWithConstants,
			signature.toString().toCharArray(),
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}
/**
 * The equivalent code performs a string conversion:
 *
 * @param blockScope the given blockScope
 * @param oper1 the first expression
 * @param oper2 the second expression
 */
public void generateStringConcatenationAppend(BlockScope blockScope, Expression oper1, Expression oper2) {
	if (this.targetLevel >= ClassFileConstants.JDK9 && blockScope.compilerOptions().useStringConcatFactory) {
		this.countLabels = 0;
		StringBuilder recipe = new StringBuilder();
		List<TypeBinding> arguments = new ArrayList<>();
		if (oper1 == null) {
			// Operand is already on the stack
			invokeStringValueOf(TypeIds.T_JavaLangObject);
			arguments.add(blockScope.getJavaLangString());
			recipe.append(TypeConstants.STRING_CONCAT_FACTORY_TAG_ARG);
		} else {
			oper1.buildStringForConcatenation(blockScope, this, oper1.implicitConversion & TypeIds.COMPILE_TYPE_MASK, recipe, arguments);
		}
		oper2.buildStringForConcatenation(blockScope, this, oper2.implicitConversion & TypeIds.COMPILE_TYPE_MASK, recipe, arguments);
		invokeDynamicForStringConcat(recipe, arguments);
	} else {
		int pc;
		if (oper1 == null) {
			/* Operand is already on the stack, and maybe nil:
			note type1 is always to  java.lang.String here.*/
			newStringContatenation();
			dup_x1();
			this.swap();
			// If argument is reference type, need to transform it
			// into a string (handles null case)
			invokeStringValueOf(TypeIds.T_JavaLangObject);
			invokeStringConcatenationStringConstructor();
		} else {
			pc = this.position;
			oper1.generateOptimizedStringConcatenationCreation(blockScope, this, oper1.implicitConversion & TypeIds.COMPILE_TYPE_MASK);
			this.recordPositionsFrom(pc, oper1.sourceStart);
		}
		pc = this.position;
		oper2.generateOptimizedStringConcatenation(blockScope, this, oper2.implicitConversion & TypeIds.COMPILE_TYPE_MASK);
		this.recordPositionsFrom(pc, oper2.sourceStart);
		invokeStringConcatenationToString();
	}
}

/**
 * @param accessBinding the access method binding to generate
 */
public void generateSyntheticBodyForConstructorAccess(SyntheticMethodBinding accessBinding) {
	MethodBinding constructorBinding = accessBinding.targetMethod;
	TypeBinding[] parameters = constructorBinding.parameters;
	int length = parameters.length;
	int resolvedPosition = 1;
	aload_0();
	// special name&ordinal argument generation for enum constructors
	TypeBinding declaringClass = constructorBinding.declaringClass;
	if (declaringClass.erasure().id == TypeIds.T_JavaLangEnum || declaringClass.isEnum()) {
		aload_1(); // pass along name param as name arg
		iload_2(); // pass along ordinal param as ordinal arg
		resolvedPosition += 2;
	}
	if (declaringClass.isNestedType()) {
		NestedTypeBinding nestedType = (NestedTypeBinding) declaringClass;
		SyntheticArgumentBinding[] syntheticArguments = nestedType.syntheticEnclosingInstances();
		for (int i = 0; i < (syntheticArguments == null ? 0 : syntheticArguments.length); i++) {
			TypeBinding type;
			load((type = syntheticArguments[i].type), resolvedPosition);
			switch(type.id) {
				case TypeIds.T_long :
				case TypeIds.T_double :
					resolvedPosition += 2;
					break;
				default :
					resolvedPosition++;
					break;
			}
		}
	}
	for (int i = 0; i < length; i++) {
		TypeBinding parameter;
		load(parameter = parameters[i], resolvedPosition);
		switch(parameter.id) {
			case TypeIds.T_long :
			case TypeIds.T_double :
				resolvedPosition += 2;
				break;
			default :
				resolvedPosition++;
				break;
		}
	}

	if (declaringClass.isNestedType()) {
		NestedTypeBinding nestedType = (NestedTypeBinding) declaringClass;
		SyntheticArgumentBinding[] syntheticArguments = nestedType.syntheticOuterLocalVariables();
		for (int i = 0; i < (syntheticArguments == null ? 0 : syntheticArguments.length); i++) {
			TypeBinding type;
			load(type = syntheticArguments[i].type, resolvedPosition);
			switch(type.id) {
				case TypeIds.T_long :
				case TypeIds.T_double :
					resolvedPosition += 2;
					break;
				default :
					resolvedPosition++;
					break;
			}
		}
	}
	invoke(Opcodes.OPC_invokespecial, constructorBinding, null /* default declaringClass */);
	return_();
}
public void generateSyntheticBodyForArrayConstructor(SyntheticMethodBinding methodBinding) {
	iload_0();
	newArray(null, null, (ArrayBinding) methodBinding.returnType);
	areturn();
}
public void generateSyntheticBodyForArrayClone(SyntheticMethodBinding methodBinding) {
	TypeBinding arrayType = methodBinding.parameters[0];
	aload_0();
	invoke(   // // invokevirtual: "[I".clone:()Ljava/lang/Object;
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			arrayType.signature(), // declaring class e.g "[I"
			ConstantPool.Clone,
			ConstantPool.CloneSignature,
			getPopularBinding(ConstantPool.JavaLangObjectConstantPoolName));
	checkcast(arrayType);
	areturn();
}
public void generateSyntheticBodyForFactoryMethod(SyntheticMethodBinding methodBinding) {
	MethodBinding constructorBinding = methodBinding.targetMethod;
	TypeBinding[] parameters = methodBinding.parameters;
	int length = parameters.length;

	new_(constructorBinding.declaringClass);
	dup();

	int resolvedPosition = 0;
	for (int i = 0; i < length; i++) {
		TypeBinding parameter;
		load(parameter = parameters[i], resolvedPosition);
		switch(parameter.id) {
			case TypeIds.T_long :
			case TypeIds.T_double :
				resolvedPosition += 2;
				break;
			default :
				resolvedPosition++;
				break;
		}
	}
	for (int i = 0; i < methodBinding.fakePaddedParameters; i++)
		aconst_null();

	invoke(Opcodes.OPC_invokespecial, constructorBinding, null /* default declaringClass */);
	areturn();
}
//static X valueOf(String name) {
// return (X) Enum.valueOf(X.class, name);
//}
public void generateSyntheticBodyForEnumValueOf(SyntheticMethodBinding methodBinding) {
	final ReferenceBinding declaringClass = methodBinding.declaringClass;
	generateClassLiteralAccessForType(((SourceTypeBinding) methodBinding.declaringClass).scope, declaringClass);
	aload_0();
	invokeJavaLangEnumvalueOf(declaringClass);
	this.checkcast(declaringClass);
	areturn();
}

// TODO what about blowing the method limit? Ignore for now?
/**
 * This is intended to match what javac generates. First there is a switch statement on the hashcode of the lambda method name - based on that
 * an id is computed (0..N). An unrecognized hash gets the id -1. Then a second switch is on the id and each case here checks all the properties
 * of the serialized lambda. If they all checkout OK an invokedynamic call to a bootstrap method targeting the altMetafactory. If any of the tests
 * fail an IllegalArgumentException is thrown. This exception is not typically seen by the 'user', instead they seem to see a NPE when
 * the lambda does not deserialize properly.
 */
public void generateSyntheticBodyForDeserializeLambda(SyntheticMethodBinding methodBinding,SyntheticMethodBinding[] syntheticMethodBindings) {
	// Compute a map of hashcodes to a list of synthetic methods whose names share a hashcode
	Map hashcodesTosynthetics = new LinkedHashMap();
	for (SyntheticMethodBinding syntheticMethodBinding : syntheticMethodBindings) {
		if (syntheticMethodBinding.lambda!=null && syntheticMethodBinding.lambda.isSerializable ||
				syntheticMethodBinding.serializableMethodRef != null) {
			// TODO can I use > Java 1.4 features here?
			Integer hashcode = Integer.valueOf(new String(syntheticMethodBinding.selector).hashCode());
			List syntheticssForThisHashcode = (List)hashcodesTosynthetics.get(hashcode);
			if (syntheticssForThisHashcode==null) {
				syntheticssForThisHashcode = new ArrayList();
				hashcodesTosynthetics.put(hashcode,syntheticssForThisHashcode);
			}
			syntheticssForThisHashcode.add(syntheticMethodBinding);
		}
	}
	ClassScope scope = ((SourceTypeBinding)methodBinding.declaringClass).scope;

	// Generate the first switch, on method name hashcode
	aload_0();
	invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName, ConstantPool.GetImplMethodName, ConstantPool.GetImplMethodNameSignature,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
	astore_1();
	LocalVariableBinding lvb1 = new LocalVariableBinding("hashcode".toCharArray(),scope.getJavaLangString(),0,false); //$NON-NLS-1$
	lvb1.resolvedPosition = 1;
	addVariable(lvb1);
	iconst_m1();
	istore_2();
	LocalVariableBinding lvb2 = new LocalVariableBinding("id".toCharArray(),TypeBinding.INT,0,false); //$NON-NLS-1$
	lvb2.resolvedPosition = 2;
	addVariable(lvb2);
	aload_1();
	invokeStringHashCode();

	BranchLabel label = new BranchLabel(this);
	CaseLabel defaultLabel = new CaseLabel(this);
	int numberOfHashcodes = hashcodesTosynthetics.size();
	CaseLabel[] switchLabels = new CaseLabel[numberOfHashcodes];
	int[] keys = new int[numberOfHashcodes];
	int[] sortedIndexes = new int[numberOfHashcodes];
	Set hashcodes = hashcodesTosynthetics.keySet();
	Iterator hashcodeIterator = hashcodes.iterator();
	int index=0;
	while (hashcodeIterator.hasNext()) {
		Integer hashcode = (Integer)hashcodeIterator.next();
		switchLabels[index] = new CaseLabel(this);
		keys[index] = hashcode.intValue();
		sortedIndexes[index] = index;
		index++;
	}
	int[] localKeysCopy;
	System.arraycopy(keys,0,(localKeysCopy = new int[numberOfHashcodes]),0,numberOfHashcodes);
	sort(localKeysCopy, 0, numberOfHashcodes-1, sortedIndexes);
	// TODO need to use a tableswitch at some size threshold?
	lookupswitch(defaultLabel, keys, sortedIndexes, switchLabels);
	// TODO cope with multiple names that share the same hashcode
	hashcodeIterator = hashcodes.iterator();
	index = 0;
	while (hashcodeIterator.hasNext()) {
		Integer hashcode = (Integer)hashcodeIterator.next();
		List synthetics = (List)hashcodesTosynthetics.get(hashcode);
		switchLabels[index].place();
		BranchLabel nextOne = new BranchLabel(this);
		// Loop through all lambdas that share the same hashcode
		// TODO: isn't doing this for just one of these enough because they all share
		// the same name?
		for (Object synthetic : synthetics) {
			SyntheticMethodBinding syntheticMethodBinding = (SyntheticMethodBinding)synthetic;
			aload_1();
			ldc(new String(syntheticMethodBinding.selector));
			invokeStringEquals();
			ifeq(nextOne);
			loadInt(index);
			istore_2();
			goto_(label);
			nextOne.place();
			nextOne = new BranchLabel(this);
		}
		index++;
		goto_(label);
	}
	defaultLabel.place();
	label.place();
	int syntheticsCount = hashcodes.size();
	// Second block is switching on the lambda id, -1 is the error (unrecognized) case
	switchLabels = new CaseLabel[syntheticsCount];
	keys = new int[syntheticsCount];
	sortedIndexes = new int[syntheticsCount];
	BranchLabel errorLabel = new BranchLabel(this);
	defaultLabel = new CaseLabel(this);
	iload_2();
	for (int j=0;j<syntheticsCount;j++) {
		switchLabels[j] = new CaseLabel(this);
		keys[j] = j;
		sortedIndexes[j] = j;
	}
	System.arraycopy(keys,0,(localKeysCopy = new int[syntheticsCount]),0,syntheticsCount);
	// TODO no need to sort here? They should all be in order
	sort(localKeysCopy, 0, syntheticsCount-1, sortedIndexes);
	// TODO need to use a tableswitch at some size threshold?
	lookupswitch(defaultLabel, keys, sortedIndexes, switchLabels);
	hashcodeIterator = hashcodes.iterator();
	int hashcodeIndex = 0;
	while (hashcodeIterator.hasNext()) {
		Integer hashcode = (Integer)hashcodeIterator.next();
		List synthetics = (List)hashcodesTosynthetics.get(hashcode);
		switchLabels[hashcodeIndex++].place();
		BranchLabel nextOne = synthetics.size() > 1 ? new BranchLabel(this) : errorLabel;
		// Loop through all lambdas that share the same hashcode
		for (int j = 0, count = synthetics.size(); j < count; j++) {
			SyntheticMethodBinding syntheticMethodBinding = (SyntheticMethodBinding) synthetics.get(j);
			// Compare ImplMethodKind
			aload_0();
			FunctionalExpression funcEx = syntheticMethodBinding.lambda != null ? syntheticMethodBinding.lambda
					: syntheticMethodBinding.serializableMethodRef;
			MethodBinding mb = funcEx.binding;
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetImplMethodKind, ConstantPool.GetImplMethodKindSignature, TypeIds.T_int,
					TypeBinding.INT);
			byte methodKind = 0;
			if (mb.isStatic()) {
				methodKind = ClassFileConstants.MethodHandleRefKindInvokeStatic;
			} else if (mb.isPrivate()) {
				methodKind = ClassFileConstants.MethodHandleRefKindInvokeSpecial;
			} else if (mb.isConstructor()) {
				methodKind = ClassFileConstants.MethodHandleRefKindNewInvokeSpecial;
			} else if (mb.declaringClass.isInterface()) {
				methodKind = ClassFileConstants.MethodHandleRefKindInvokeInterface;
			} else {
				methodKind = ClassFileConstants.MethodHandleRefKindInvokeVirtual;
			}
			bipush(methodKind);// TODO see table below
			if_icmpne(nextOne);

			// Compare FunctionalInterfaceClass
			aload_0();
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetFunctionalInterfaceClass, ConstantPool.GetFunctionalInterfaceClassSignature,
					getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
			String functionalInterface = null;
			final TypeBinding expectedType = funcEx.expectedType();
			if (expectedType instanceof IntersectionTypeBinding18) {
				functionalInterface = new String(
						((IntersectionTypeBinding18) expectedType).getSAMType(scope).constantPoolName());
			} else {
				functionalInterface = new String(expectedType.constantPoolName());
			}
			ldc(functionalInterface);// e.g. "com/foo/X$Foo"
			invokeObjectEquals();
			ifeq(nextOne);

			// Compare FunctionalInterfaceMethodName
			aload_0();
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetFunctionalInterfaceMethodName,
					ConstantPool.GetFunctionalInterfaceMethodNameSignature,
					getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
			ldc(new String(funcEx.descriptor.selector)); // e.g. "m"
			invokeObjectEquals();
			ifeq(nextOne);

			// Compare FunctionalInterfaceMethodSignature
			aload_0();
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetFunctionalInterfaceMethodSignature,
					ConstantPool.GetFunctionalInterfaceMethodSignatureSignature,
					getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
			ldc(new String(funcEx.descriptor.original().signature())); // e.g "()I"
			invokeObjectEquals();
			ifeq(nextOne);

			// Compare ImplClass
			aload_0();
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetImplClass, ConstantPool.GetImplClassSignature,
					getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
			ldc(new String(mb.declaringClass.constantPoolName())); // e.g. "com/foo/X"
			invokeObjectEquals();
			ifeq(nextOne);

			// Compare ImplMethodSignature
			aload_0();
			invoke(Opcodes.OPC_invokevirtual, 1, 1, ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
					ConstantPool.GetImplMethodSignature, ConstantPool.GetImplMethodSignatureSignature,
					getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
			ldc(new String(mb.original().signature())); // e.g. "(I)I"
			invokeObjectEquals();
			ifeq(nextOne);

			// Captured arguments
			StringBuilder sig = new StringBuilder("("); //$NON-NLS-1$
			index = 0;
			boolean isLambda = funcEx instanceof LambdaExpression;
			TypeBinding receiverType = null;
			SyntheticArgumentBinding[] outerLocalVariables = null;
			if (isLambda) {
				LambdaExpression lambdaEx = (LambdaExpression) funcEx;
				if (lambdaEx.shouldCaptureInstance)
					receiverType = mb.declaringClass;
				outerLocalVariables = lambdaEx.outerLocalVariables;
			} else {
				ReferenceExpression refEx = (ReferenceExpression)funcEx;
				if (refEx.haveReceiver)
					receiverType = ((ReferenceExpression)funcEx).receiverType;
				// Should never have outer locals
			}
			if (receiverType != null) {
				aload_0();
				loadInt(index++);
				invoke(Opcodes.OPC_invokevirtual, 1, 1,
						ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
						ConstantPool.GetCapturedArg, ConstantPool.GetCapturedArgSignature,
						getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
				checkcast(receiverType);
				sig.append(receiverType.signature());
			}
			for (int p = 0, max = outerLocalVariables == null ? 0 : outerLocalVariables.length; p < max; p++) {
				TypeBinding varType = outerLocalVariables[p].type;
				aload_0();
				loadInt(index);
				invoke(Opcodes.OPC_invokevirtual, 1, 1,
						ConstantPool.JavaLangInvokeSerializedLambdaConstantPoolName,
						ConstantPool.GetCapturedArg, ConstantPool.GetCapturedArgSignature,
						getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
				if (varType.isBaseType()) {
					checkcast(scope.boxing(varType));
					generateUnboxingConversion(varType.id);
					if (varType.id == TypeIds.T_JavaLangLong || varType.id == TypeIds.T_JavaLangDouble) {
						index++;
					}
				} else {
					checkcast(varType);
				}
				index++;
				sig.append(varType.signature());
			}
			sig.append(")"); //$NON-NLS-1$
			if (funcEx.resolvedType instanceof IntersectionTypeBinding18) {
				sig.append(((IntersectionTypeBinding18) funcEx.resolvedType).getSAMType(scope).signature());
			} else {
				sig.append(funcEx.resolvedType.signature());
			}
			// Example: invokeDynamic(0, 0, 1, "m".toCharArray(), "()Lcom/foo/X$Foo;".toCharArray());
			invokeDynamic(funcEx.bootstrapMethodNumber, index, 1, funcEx.descriptor.selector,
					sig.toString().toCharArray(), funcEx.resolvedType);
			areturn();
			if (j < count - 1) {
				nextOne.place();
				nextOne = j < count - 2 ? new BranchLabel(this) : errorLabel;
			}
		}
	}

	removeVariable(lvb1);
	removeVariable(lvb2);
	defaultLabel.place();
	errorLabel.place();
	// Code: throw new IllegalArgumentException("Invalid lambda deserialization")
	new_(scope.getJavaLangIllegalArgumentException());
	dup();
	ldc("Invalid lambda deserialization"); //$NON-NLS-1$ // TODO into a constant?
	// invokespecial: java.lang.IllegalArgumentException.<init>(Ljava/lang/String;)V
	invoke(
			Opcodes.OPC_invokespecial,
			2, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangIllegalArgumentExceptionConstantPoolName,
			ConstantPool.Init,
			ConstantPool.IllegalArgumentExceptionConstructorSignature,
			null);
	athrow();
}

/**
 * Based on the supplied value add the most efficient load instruction to the code stream for that value.
 * Note: Does not handle  negative values.
 */
public void loadInt(int value) {
	if (value<6) {
		if (value==0) {
			iconst_0();
		} else if (value==1) {
			iconst_1();
		} else if (value==2) {
			iconst_2();
		} else if (value==3) {
			iconst_3();
		} else if (value==4) {
			iconst_4();
		} else if (value==5) {
			iconst_5();
		}
	} else if (value < 128) {
		// TODO [andy] testcases that hit this
		bipush((byte)value);
	} else {
		// TODO [andy] testcases that hit this, yikes
		ldc(value);
	}
}

//static X[] values() {
// X[] values;
// int length;
// X[] result;
// System.arraycopy(values = $VALUES, 0, result = new X[length= values.length], 0, length)
// return result;
//}
public void generateSyntheticBodyForEnumValues(SyntheticMethodBinding methodBinding) {
	ClassScope scope = ((SourceTypeBinding)methodBinding.declaringClass).scope;
	TypeBinding enumArray = methodBinding.returnType;
	fieldAccess(Opcodes.OPC_getstatic, scope.referenceContext.enumValuesSyntheticfield, null /* default declaringClass */);
	dup();
	astore_0();
	iconst_0();
	aload_0();
	arraylength();
	dup();
	istore_1();
	newArray((ArrayBinding) enumArray);
	dup();
	astore_2();
	iconst_0();
	iload_1();
	invokeSystemArraycopy();
	aload_2();
	areturn();
}
public void generateSyntheticBodyForEnumInitializationMethod(SyntheticMethodBinding methodBinding) {
	// generate all enum constants
	SourceTypeBinding sourceTypeBinding = (SourceTypeBinding) methodBinding.declaringClass;
	TypeDeclaration typeDeclaration = sourceTypeBinding.scope.referenceContext;
	BlockScope staticInitializerScope = typeDeclaration.staticInitializerScope;
	FieldDeclaration[] fieldDeclarations = typeDeclaration.fields;
	for (int i = methodBinding.startIndex, max = methodBinding.endIndex; i < max; i++) {
		FieldDeclaration fieldDecl = fieldDeclarations[i];
		if (fieldDecl.isStatic()) {
			if (fieldDecl.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT) {
				fieldDecl.generateCode(staticInitializerScope, this);
			}
		}
	}
	return_();
}
public void generateSyntheticBodyForFieldReadAccess(SyntheticMethodBinding accessMethod) {
	VariableBinding fieldBinding = accessMethod.targetReadField;
	// target method declaring class may not be accessible (247953);
	TypeBinding declaringClass = accessMethod.purpose == SyntheticMethodBinding.SuperFieldReadAccess
			? accessMethod.declaringClass.superclass()
			: accessMethod.declaringClass;
	if (fieldBinding.isStatic()) {
		fieldAccess(Opcodes.OPC_getstatic, fieldBinding, declaringClass);
	} else {
		aload_0();
		fieldAccess(Opcodes.OPC_getfield, fieldBinding, declaringClass);
	}
	switch (fieldBinding.type.id) {
//		case T_void :
//			this.return_();
//			break;
		case TypeIds.T_boolean :
		case TypeIds.T_byte :
		case TypeIds.T_char :
		case TypeIds.T_short :
		case TypeIds.T_int :
			ireturn();
			break;
		case TypeIds.T_long :
			lreturn();
			break;
		case TypeIds.T_float :
			freturn();
			break;
		case TypeIds.T_double :
			dreturn();
			break;
		default :
			areturn();
	}
}

public void generateSyntheticBodyForFieldWriteAccess(SyntheticMethodBinding accessMethod) {
	FieldBinding fieldBinding = accessMethod.targetWriteField;
	// target method declaring class may not be accessible (247953);
	TypeBinding declaringClass = accessMethod.purpose == SyntheticMethodBinding.SuperFieldWriteAccess
			? accessMethod.declaringClass.superclass()
			: accessMethod.declaringClass;
	if (fieldBinding.isStatic()) {
		load(fieldBinding.type, 0);
		fieldAccess(Opcodes.OPC_putstatic, fieldBinding, declaringClass);
	} else {
		aload_0();
		load(fieldBinding.type, 1);
		fieldAccess(Opcodes.OPC_putfield, fieldBinding, declaringClass);
	}
	return_();
}

public void generateSyntheticBodyForMethodAccess(SyntheticMethodBinding accessMethod) {
	MethodBinding targetMethod = accessMethod.targetMethod;
	TypeBinding[] parameters = targetMethod.parameters;
	int length = parameters.length;
	TypeBinding[] arguments = accessMethod.purpose == SyntheticMethodBinding.BridgeMethod
													? accessMethod.parameters
													: null;
	int resolvedPosition;
	if (targetMethod.isStatic())
		resolvedPosition = 0;
	else {
		aload_0();
		resolvedPosition = 1;
	}
	for (int i = 0; i < length; i++) {
	    TypeBinding parameter = parameters[i];
	    if (arguments != null) { // for bridge methods
		    TypeBinding argument = arguments[i];
			load(argument, resolvedPosition);
			if (TypeBinding.notEquals(argument, parameter))
			    checkcast(parameter);
	    } else {
			load(parameter, resolvedPosition);
		}
		switch(parameter.id) {
			case TypeIds.T_long :
			case TypeIds.T_double :
				resolvedPosition += 2;
				break;
			default :
				resolvedPosition++;
				break;
		}
	}
	if (targetMethod.isStatic())
		invoke(Opcodes.OPC_invokestatic, targetMethod, accessMethod.declaringClass); // target method declaring class may not be accessible (128563)
	else {
		if (targetMethod.isConstructor()
				|| targetMethod.isPrivate()
				// qualified super "X.super.foo()" targets methods from superclass
				|| accessMethod.purpose == SyntheticMethodBinding.SuperMethodAccess){
			// target method declaring class may not be accessible (247953);
			TypeBinding declaringClass = accessMethod.purpose == SyntheticMethodBinding.SuperMethodAccess
					? findDirectSuperTypeTowards(accessMethod, targetMethod)
					: accessMethod.declaringClass;
			invoke(Opcodes.OPC_invokespecial, targetMethod, declaringClass);
		} else {
			if (targetMethod.declaringClass.isInterface()) { // interface or annotation type
				invoke(Opcodes.OPC_invokeinterface, targetMethod, null /* default declaringClass */);
			} else {
				invoke(Opcodes.OPC_invokevirtual, targetMethod, accessMethod.declaringClass); // target method declaring class may not be accessible (128563)
			}
		}
	}
	switch (targetMethod.returnType.id) {
		case TypeIds.T_void :
			return_();
			break;
		case TypeIds.T_boolean :
		case TypeIds.T_byte :
		case TypeIds.T_char :
		case TypeIds.T_short :
		case TypeIds.T_int :
			ireturn();
			break;
		case TypeIds.T_long :
			lreturn();
			break;
		case TypeIds.T_float :
			freturn();
			break;
		case TypeIds.T_double :
			dreturn();
			break;
		default :
			TypeBinding accessErasure = accessMethod.returnType.erasure();
			TypeBinding match = targetMethod.returnType.findSuperTypeOriginatingFrom(accessErasure);
			if (match == null) {
				this.checkcast(accessErasure); // for bridge methods
			}
			areturn();
	}
}
/** When generating SuperMetodAccess towards targetMethod,
 *  find the suitable direct super type, that will eventually lead to targetMethod.declaringClass.*/
ReferenceBinding findDirectSuperTypeTowards(SyntheticMethodBinding accessMethod, MethodBinding targetMethod) {
	ReferenceBinding currentType = accessMethod.declaringClass;
	ReferenceBinding superclass = currentType.superclass();
	if (targetMethod.isDefaultMethod()) {
		// could be inherited via superclass *or* a super interface
		ReferenceBinding targetType = targetMethod.declaringClass;
		if (superclass.isCompatibleWith(targetType))
			return superclass;
		ReferenceBinding[] superInterfaces = currentType.superInterfaces();
		if (superInterfaces != null) {
			for (ReferenceBinding superIfc : superInterfaces) {
				if (superIfc.isCompatibleWith(targetType))
					return superIfc;
			}
		}
		throw new RuntimeException("Assumption violated: some super type must be conform to the declaring class of a super method"); //$NON-NLS-1$
	} else {
		// only one path possible:
		return superclass;
	}
}

public void generateSyntheticBodyForSwitchTable(SyntheticMethodBinding methodBinding) {
	ClassScope scope = ((SourceTypeBinding)methodBinding.declaringClass).scope;
	final BranchLabel nullLabel = new BranchLabel(this);
	VariableBinding syntheticFieldBinding = methodBinding.targetReadField;
	fieldAccess(Opcodes.OPC_getstatic, syntheticFieldBinding, null /* default declaringClass */);
	dup();
	ifnull(nullLabel);
	areturn();
	pushOnStack(syntheticFieldBinding.type);
	nullLabel.place();
	pop();
	ReferenceBinding enumBinding = (ReferenceBinding) methodBinding.targetEnumType;
	ArrayBinding arrayBinding = scope.createArrayType(enumBinding, 1);
	invokeJavaLangEnumValues(enumBinding, arrayBinding);
	arraylength();
	newarray(ClassFileConstants.INT_ARRAY);
	astore_0();
	LocalVariableBinding localVariableBinding = new LocalVariableBinding(" tab".toCharArray(), scope.createArrayType(TypeBinding.INT, 1), 0, false); //$NON-NLS-1$
	addVariable(localVariableBinding);
	final FieldBinding[] fields = enumBinding.fields();
	if (fields != null) {
		for (FieldBinding fieldBinding : fields) {
			if ((fieldBinding.getAccessFlags() & ClassFileConstants.AccEnum) != 0) {
				final BranchLabel endLabel = new BranchLabel(this);
				final ExceptionLabel anyExceptionHandler = new ExceptionLabel(this, TypeBinding.LONG /* represents NoSuchFieldError*/);
				anyExceptionHandler.placeStart();
				aload_0();
				fieldAccess(Opcodes.OPC_getstatic, fieldBinding, null /* default declaringClass */);
				invokeEnumOrdinal(enumBinding.constantPoolName());
				this.generateInlinedValue(fieldBinding.id + 1); // zero should not be returned see bug 141810
				iastore();
				anyExceptionHandler.placeEnd();
				goto_(endLabel);
				// Generate the body of the exception handler
				pushExceptionOnStack(scope.getJavaLangNoSuchFieldError());
				anyExceptionHandler.place();
				pop(); // we don't use it so we can pop it
				endLabel.place();
			}
		}
	}
	aload_0();
	if (scope.compilerOptions().complianceLevel < ClassFileConstants.JDK9 || !syntheticFieldBinding.isFinal()) {
		// Modifying a final field outside of the <clinit> method is not allowed in 9
		dup();
		fieldAccess(Opcodes.OPC_putstatic, syntheticFieldBinding, null /* default declaringClass */);
	}
	areturn();
	removeVariable(localVariableBinding);
}

/**
 * Code responsible to generate the suitable code to supply values for the synthetic enclosing
 * instance arguments of a constructor invocation of a nested type.
 */
public void generateSyntheticEnclosingInstanceValues(BlockScope currentScope, ReferenceBinding targetType, Expression enclosingInstance, ASTNode invocationSite) {
	// supplying enclosing instance for the anonymous type's superclass
	ReferenceBinding checkedTargetType = targetType.isAnonymousType() ? (ReferenceBinding)targetType.superclass().erasure() : targetType;
	boolean hasExtraEnclosingInstance = enclosingInstance != null;
	if (hasExtraEnclosingInstance
			&& (!checkedTargetType.isNestedType() || checkedTargetType.isStatic())) {
		currentScope.problemReporter().unnecessaryEnclosingInstanceSpecification(enclosingInstance, checkedTargetType);
		return;
	}

	// perform some emulation work in case there is some and we are inside a local type only
	ReferenceBinding[] syntheticArgumentTypes;
	if ((syntheticArgumentTypes = targetType.syntheticEnclosingInstanceTypes()) != null) {

		ReferenceBinding targetEnclosingType = checkedTargetType.enclosingType();

		// deny access to enclosing instance argument for allocation and super constructor call (if 1.4)
		// always consider it if complying to 1.5
		boolean denyEnclosingArgInConstructorCall;
		//compliance >= JDK1_7
		if (invocationSite instanceof AllocationExpression) {
			denyEnclosingArgInConstructorCall = !targetType.isLocalType();
		} else if (invocationSite instanceof ExplicitConstructorCall &&
				((ExplicitConstructorCall)invocationSite).isSuperAccess()) {
			MethodScope enclosingMethodScope = currentScope.enclosingMethodScope();
			denyEnclosingArgInConstructorCall = !targetType.isLocalType() && enclosingMethodScope != null
					&& enclosingMethodScope.isConstructorCall;
		} else {
			denyEnclosingArgInConstructorCall = false;
		}

		for (ReferenceBinding syntheticArgType : syntheticArgumentTypes) {
			if (hasExtraEnclosingInstance && TypeBinding.equalsEquals(syntheticArgType, targetEnclosingType)) {
				hasExtraEnclosingInstance = false;
				enclosingInstance.generateCode(currentScope, this, true);
				dup();
				invokeObjectGetClass(); // will perform null check
				pop();
			} else {
				Object[] emulationPath = currentScope.getEmulationPath(
						syntheticArgType,
						false /*not only exact match (that is, allow compatible)*/,
						denyEnclosingArgInConstructorCall);
				generateOuterAccess(emulationPath, invocationSite, syntheticArgType, currentScope);
			}
		}
		if (hasExtraEnclosingInstance){
			currentScope.problemReporter().unnecessaryEnclosingInstanceSpecification(enclosingInstance, checkedTargetType);
		}
	}
}

/**
 * Code responsible to generate the suitable code to supply values for the synthetic outer local
 * variable arguments of a constructor invocation of a nested type.
 * (bug 26122) - synthetic values for outer locals must be passed after user arguments, e.g. new X(i = 1){}
 */
public void generateSyntheticOuterArgumentValues(BlockScope currentScope, ReferenceBinding targetType, ASTNode invocationSite) {
	// generate the synthetic outer arguments then
	SyntheticArgumentBinding syntheticArguments[];
	if ((syntheticArguments = targetType.syntheticOuterLocalVariables()) != null) {
		for (SyntheticArgumentBinding syntheticArgument : syntheticArguments) {
			LocalVariableBinding targetVariable = syntheticArgument.actualOuterLocalVariable;
			VariableBinding[] emulationPath = currentScope.getEmulationPath(targetVariable);
			generateOuterAccess(emulationPath, invocationSite, targetVariable, currentScope);
		}
	}
}
public void generateSyntheticBodyForRecordCanonicalConstructor(SyntheticMethodBinding canonConstructor) {
	SourceTypeBinding declaringClass = (SourceTypeBinding) canonConstructor.declaringClass;
	ReferenceBinding superClass = declaringClass.superclass();
	MethodBinding superCons = superClass.getExactConstructor(new TypeBinding[0]);
	aload_0();
	invoke(Opcodes.OPC_invokespecial, superCons, superClass);
	int resolvedPosition;
	VariableBinding[] fields =  declaringClass.components();
	int len = fields != null ? fields.length : 0;
	resolvedPosition = 1;
	for (int i = 0;  i < len; ++i) {
		VariableBinding field = fields[i];
		aload_0();
	    TypeBinding type = field.type;
		load(type, resolvedPosition);
		switch(type.id) {
			case TypeIds.T_long :
			case TypeIds.T_double :
				resolvedPosition += 2;
				break;
			default :
				resolvedPosition++;
				break;
		}
		fieldAccess(Opcodes.OPC_putfield, field, declaringClass);
	}
	return_();
}
public void generateSyntheticBodyForRecordEquals(SyntheticMethodBinding methodBinding, int index) {
	aload_0();
	aload_1();
	String sig = new String(methodBinding.signature());
	sig = sig.substring(0, 1)+ new String(methodBinding.declaringClass.signature()) + sig.substring(1);
	invokeDynamic(index, methodBinding.parameters.length, 1, methodBinding.selector, sig.toCharArray(),
			TypeBinding.BOOLEAN);
	ireturn();
}
public void generateSyntheticBodyForRecordHashCode(SyntheticMethodBinding methodBinding, int index) {
	aload_0();
	String sig = new String(methodBinding.signature());
	sig = sig.substring(0, 1)+ new String(methodBinding.declaringClass.signature()) + sig.substring(1);
	invokeDynamic(index, methodBinding.parameters.length, 1, methodBinding.selector, sig.toCharArray(),
			TypeBinding.INT);
	ireturn();
}
public void generateSyntheticBodyForRecordToString(SyntheticMethodBinding methodBinding, int index) {
	aload_0();
	String sig = new String(methodBinding.signature());
	sig = sig.substring(0, 1)+ new String(methodBinding.declaringClass.signature()) + sig.substring(1);
	invokeDynamic(index, methodBinding.parameters.length, 1, methodBinding.selector, sig.toCharArray(),
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
	areturn();
}

public void generateUnboxingConversion(int unboxedTypeID) {
	switch (unboxedTypeID) {
		case TypeIds.T_byte :
			// invokevirtual: byteValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangByteConstantPoolName,
					ConstantPool.BYTEVALUE_BYTE_METHOD_NAME,
					ConstantPool.BYTEVALUE_BYTE_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_short :
			// invokevirtual: shortValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangShortConstantPoolName,
					ConstantPool.SHORTVALUE_SHORT_METHOD_NAME,
					ConstantPool.SHORTVALUE_SHORT_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_char :
			// invokevirtual: charValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangCharacterConstantPoolName,
					ConstantPool.CHARVALUE_CHARACTER_METHOD_NAME,
					ConstantPool.CHARVALUE_CHARACTER_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_int :
			// invokevirtual: intValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangIntegerConstantPoolName,
					ConstantPool.INTVALUE_INTEGER_METHOD_NAME,
					ConstantPool.INTVALUE_INTEGER_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_long :
			// invokevirtual: longValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					2, // return type size
					ConstantPool.JavaLangLongConstantPoolName,
					ConstantPool.LONGVALUE_LONG_METHOD_NAME,
					ConstantPool.LONGVALUE_LONG_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_float :
			// invokevirtual: floatValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangFloatConstantPoolName,
					ConstantPool.FLOATVALUE_FLOAT_METHOD_NAME,
					ConstantPool.FLOATVALUE_FLOAT_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_double :
			// invokevirtual: doubleValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					2, // return type size
					ConstantPool.JavaLangDoubleConstantPoolName,
					ConstantPool.DOUBLEVALUE_DOUBLE_METHOD_NAME,
					ConstantPool.DOUBLEVALUE_DOUBLE_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
			break;
		case TypeIds.T_boolean :
			// invokevirtual: booleanValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangBooleanConstantPoolName,
					ConstantPool.BOOLEANVALUE_BOOLEAN_METHOD_NAME,
					ConstantPool.BOOLEANVALUE_BOOLEAN_METHOD_SIGNATURE,
					unboxedTypeID,
					TypeBinding.wellKnownBaseType(unboxedTypeID));
	}
}


/*
 * Wide conditional branch compare, improved by swapping comparison opcode
 *   ifeq WideTarget
 * becomes
 *    ifne Intermediate
 *    gotow WideTarget
 *    Intermediate:
 */
public void generateWideRevertedConditionalBranch(byte revertedOpcode, BranchLabel wideTarget) {
		BranchLabel intermediate = new BranchLabel(this);
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = revertedOpcode;
		intermediate.branch();
		goto_w(wideTarget);
		intermediate.place();
}

public void getBaseTypeValue(int baseTypeID) {
	switch (baseTypeID) {
		case TypeIds.T_byte :
			// invokevirtual: byteValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangByteConstantPoolName,
					ConstantPool.BYTEVALUE_BYTE_METHOD_NAME,
					ConstantPool.BYTEVALUE_BYTE_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_short :
			// invokevirtual: shortValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangShortConstantPoolName,
					ConstantPool.SHORTVALUE_SHORT_METHOD_NAME,
					ConstantPool.SHORTVALUE_SHORT_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_char :
			// invokevirtual: charValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangCharacterConstantPoolName,
					ConstantPool.CHARVALUE_CHARACTER_METHOD_NAME,
					ConstantPool.CHARVALUE_CHARACTER_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_int :
			// invokevirtual: intValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangIntegerConstantPoolName,
					ConstantPool.INTVALUE_INTEGER_METHOD_NAME,
					ConstantPool.INTVALUE_INTEGER_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_long :
			// invokevirtual: longValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					2, // return type size
					ConstantPool.JavaLangLongConstantPoolName,
					ConstantPool.LONGVALUE_LONG_METHOD_NAME,
					ConstantPool.LONGVALUE_LONG_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_float :
			// invokevirtual: floatValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangFloatConstantPoolName,
					ConstantPool.FLOATVALUE_FLOAT_METHOD_NAME,
					ConstantPool.FLOATVALUE_FLOAT_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_double :
			// invokevirtual: doubleValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					2, // return type size
					ConstantPool.JavaLangDoubleConstantPoolName,
					ConstantPool.DOUBLEVALUE_DOUBLE_METHOD_NAME,
					ConstantPool.DOUBLEVALUE_DOUBLE_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
			break;
		case TypeIds.T_boolean :
			// invokevirtual: booleanValue()
			invoke(
					Opcodes.OPC_invokevirtual,
					1, // receiverAndArgsSize
					1, // return type size
					ConstantPool.JavaLangBooleanConstantPoolName,
					ConstantPool.BOOLEANVALUE_BOOLEAN_METHOD_NAME,
					ConstantPool.BOOLEANVALUE_BOOLEAN_METHOD_SIGNATURE,
					baseTypeID,
					TypeBinding.wellKnownBaseType(baseTypeID));
	}
}

final public byte[] getContents() {
	byte[] contents;
	System.arraycopy(this.bCodeStream, 0, contents = new byte[this.position], 0, this.position);
	return contents;
}

/**
 * Returns the type that should be substituted to original binding declaring class as the proper receiver type
 * @return the receiver type to use in constant pool
 */
public static TypeBinding getConstantPoolDeclaringClass(Scope currentScope, FieldBinding codegenBinding, TypeBinding actualReceiverType, boolean isImplicitThisReceiver) {
	ReferenceBinding constantPoolDeclaringClass = codegenBinding.declaringClass;
	// if the binding declaring class is not visible, need special action
	// for runtime compatibility on 1.2 VMs : change the declaring class of the binding
	// NOTE: from target 1.2 on, field's declaring class is touched if any different from receiver type
	// and not from Object or implicit static field access.
	if (TypeBinding.notEquals(constantPoolDeclaringClass, actualReceiverType.erasure())
			&& !actualReceiverType.isArrayType()
			&& constantPoolDeclaringClass != null // array.length
			&& codegenBinding.constant() == Constant.NotAConstant) {
		if ((constantPoolDeclaringClass.id != TypeIds.T_JavaLangObject) // no change for Object fields
				|| !constantPoolDeclaringClass.canBeSeenBy(currentScope)) {

			return actualReceiverType.erasure();
		}
	}
	return constantPoolDeclaringClass;
}

/**
 * Returns the type that should be substituted to original binding declaring class as the proper receiver type
 * @return the receiver type to use in constant pool
 */
public static TypeBinding getConstantPoolDeclaringClass(Scope currentScope, MethodBinding codegenBinding, TypeBinding actualReceiverType, boolean isImplicitThisReceiver) {
	TypeBinding constantPoolDeclaringClass = codegenBinding.declaringClass;
	// Post 1.4.0 target, array clone() invocations are qualified with array type
	// This is handled in array type #clone method binding resolution (see ArrayBinding.getCloneMethod())
	if (ArrayBinding.isArrayClone(actualReceiverType, codegenBinding)) {
		constantPoolDeclaringClass = actualReceiverType.erasure();
	} else {
		// if the binding declaring class is not visible, need special action
		// for runtime compatibility on 1.2 VMs : change the declaring class of the binding
		// NOTE: from target 1.2 on, method's declaring class is touched if any different from receiver type
		// and not from Object or implicit static method call.
		if (TypeBinding.notEquals(constantPoolDeclaringClass, actualReceiverType.erasure()) && !actualReceiverType.isArrayType()) {
			if ((codegenBinding.declaringClass.id != TypeIds.T_JavaLangObject) // no change for Object methods
					|| !codegenBinding.declaringClass.canBeSeenBy(currentScope)) {
				TypeBinding erasedReceiverType = actualReceiverType.erasure();
				if (erasedReceiverType.isIntersectionType18()) {
					actualReceiverType = erasedReceiverType; // need to peel the intersecting types below
				}
				if (actualReceiverType.isIntersectionType18()) {
					TypeBinding[] intersectingTypes = ((IntersectionTypeBinding18)actualReceiverType).getIntersectingTypes();
					for (TypeBinding intersectingType : intersectingTypes) {
						if (intersectingType.findSuperTypeOriginatingFrom(constantPoolDeclaringClass) != null) {
							constantPoolDeclaringClass = intersectingType.erasure();
							break;
						}
					}
				} else {
					constantPoolDeclaringClass = erasedReceiverType;
				}
			}
		}
	}
	return constantPoolDeclaringClass;
}

public void getClass(TypeBinding baseType) {
	this.countLabels = 0;
	char [] constantPoolName = switch (baseType.id) {
					case TypeIds.T_byte -> ConstantPool.JavaLangByteConstantPoolName;
					case TypeIds.T_short -> ConstantPool.JavaLangShortConstantPoolName;
					case TypeIds.T_char -> ConstantPool.JavaLangCharacterConstantPoolName;
					case TypeIds.T_int -> ConstantPool.JavaLangIntegerConstantPoolName;
					case TypeIds.T_long -> ConstantPool.JavaLangLongConstantPoolName;
					case TypeIds.T_float -> ConstantPool.JavaLangFloatConstantPoolName;
					case TypeIds.T_double -> ConstantPool.JavaLangDoubleConstantPoolName;
					case TypeIds.T_boolean -> ConstantPool.JavaLangBooleanConstantPoolName;
					case TypeIds.T_void -> ConstantPool.JavaLangVoidConstantPoolName;
					default-> throw new AssertionError("Unknown base type"); //$NON-NLS-1$
				};
	fieldAccess(
			Opcodes.OPC_getstatic,
			1, // return type size
			constantPoolName,
			ConstantPool.TYPE,
			ConstantPool.JavaLangClassSignature,
			getPopularBinding(ConstantPool.JavaLangClassConstantPoolName));
}

public void goto_(BranchLabel label) {
	if (this.wideMode) {
		goto_w(label);
		return;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	boolean chained = inlineForwardReferencesFromLabelsTargeting(label, this.position);
	/*
	 Possible optimization for code such as:
	 public Object foo() {
		boolean b = true;
		if (b) {
			if (b)
				return null;
		} else {
			if (b) {
				return null;
			}
		}
		return null;
	}
	The goto around the else block for the first if will
	be unreachable, because the thenClause of the second if
	returns. Also see 114894
	}*/
	if (chained && this.lastAbruptCompletion == this.position) {
		if (label.position != Label.POS_NOT_SET) { // ensure existing forward references are updated
			int[] forwardRefs = label.forwardReferences();
			for (int i = 0, max = label.forwardReferenceCount(); i < max; i++) {
				this.writePosition(label, forwardRefs[i]);
			}
			this.countLabels = 0; // backward jump, no further chaining allowed
		}
		return;
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_goto;
	label.branch();
	this.lastAbruptCompletion = this.position;
}

public void goto_w(BranchLabel label) {
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_goto_w;
	label.branchWide();
	this.lastAbruptCompletion = this.position;
}

public void i2b() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2b;
	this.operandStack.peek(TypeBinding.INT);
}

public void i2c() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2c;
	this.operandStack.peek(TypeBinding.INT);
}

public void i2d() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2d;
}

public void i2f() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2f;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(TypeBinding.FLOAT);
}

public void i2l() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2l;
}

public void i2s() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_i2s;
	this.operandStack.peek(TypeBinding.INT);
}

public void iadd() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iadd;
}

public void iaload() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.xaload();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iaload;
}

public void iand() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iand;
}

public void iastore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iastore;
}

public void iconst_0() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_0;
}

public void iconst_1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_1;
}

public void iconst_2() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_2;
}
public void iconst_3() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_3;
}

public void iconst_4() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_4;
}

public void iconst_5() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_5;
}

public void iconst_m1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iconst_m1;
}

public void idiv() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_idiv;
}

public void if_acmpeq(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth-=2;
	this.operandStack.pop(OperandCategory.ONE);
	this.operandStack.pop(OperandCategory.ONE);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_acmpne, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_acmpeq;
		lbl.branch();
	}
}

public void if_acmpne(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth-=2;
	this.operandStack.pop(OperandCategory.ONE);
	this.operandStack.pop(OperandCategory.ONE);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_acmpeq, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_acmpne;
		lbl.branch();
	}
}

public void if_icmpeq(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmpne, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmpeq;
		lbl.branch();
	}
}

public void if_icmpge(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmplt, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmpge;
		lbl.branch();
	}
}

public void if_icmpgt(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmple, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmpgt;
		lbl.branch();
	}
}

public void if_icmple(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmpgt, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmple;
		lbl.branch();
	}
}

public void if_icmplt(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmpge, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmplt;
		lbl.branch();
	}
}

public void if_icmpne(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_if_icmpeq, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_if_icmpne;
		lbl.branch();
	}
}

public void ifeq(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifne, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifeq;
		lbl.branch();
	}
}

public void ifge(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_iflt, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifge;
		lbl.branch();
	}
}

public void ifgt(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifle, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifgt;
		lbl.branch();
	}
}

public void ifle(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifgt, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifle;
		lbl.branch();
	}
}

public void iflt(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifge, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iflt;
		lbl.branch();
	}
}

public void ifne(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifeq, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifne;
		lbl.branch();
	}
}

public void ifnonnull(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifnull, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifnonnull;
		lbl.branch();
	}
}

public void ifnull(BranchLabel lbl) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.wideMode) {
		generateWideRevertedConditionalBranch(Opcodes.OPC_ifnonnull, lbl);
	} else {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ifnull;
		lbl.branch();
	}
}

final public void iinc(int index, int value) {
	this.countLabels = 0;
	if ((index > 255) || (value < -128 || value > 127)) { // have to widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iinc;
		writeUnsignedShort(index);
		writeSignedShort(value);
	} else {
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 3;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iinc;
		this.bCodeStream[this.classFileOffset++] = (byte) index;
		this.bCodeStream[this.classFileOffset++] = (byte) value;
	}
}

public void iload(int iArg) {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void iload_0() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.maxLocals <= 0) {
		this.maxLocals = 1;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload_0;
}

public void iload_1() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload_1;
}

public void iload_2() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload_2;
}

public void iload_3() {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iload_3;
}

public void imul() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_imul;
}

public void ineg() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ineg;
	this.operandStack.peek(TypeBinding.INT);
}

public void init(ClassFile targetClassFile) {
	this.classFile = targetClassFile;
	this.constantPool = targetClassFile.constantPool;
	this.bCodeStream = targetClassFile.contents;
	this.classFileOffset = targetClassFile.contentsOffset;
	this.startingClassFileOffset = this.classFileOffset;
	this.pcToSourceMapSize = 0;
	this.lastEntryPC = 0;
	this.visibleLocalsCount = 0;

	this.allLocalsCounter = 0;

	this.exceptionLabelsCounter = 0;

	this.countLabels = 0;
	this.lastAbruptCompletion = -1;

	this.stackMax = 0;
	this.stackDepth = 0;
	this.maxLocals = 0;
	this.position = 0;

	this.operandStack = null;
	this.patternAccessorMap.clear();
	this.accessorExceptionTrapScopes.clear();
	this.targetLevel = targetClassFile.targetJDK;
}

/**
 * @param methodBinding the given method binding to initialize the max locals
 */
public void initializeMaxLocals(MethodBinding methodBinding) {
	if (methodBinding == null) {
		this.maxLocals = 0;
		return;
	}
	this.maxLocals = methodBinding.isStatic() ? 0 : 1;
	ReferenceBinding declaringClass = methodBinding.declaringClass;
	// take into account enum constructor synthetic name+ordinal
	if (methodBinding.isConstructor() && declaringClass.isEnum()) {
		this.maxLocals += 2; // String and int (enum constant name+ordinal)
	}

	// take into account the synthetic parameters
	if (methodBinding.isConstructor() && declaringClass.isNestedType()) {
		this.maxLocals += declaringClass.getEnclosingInstancesSlotSize();
		this.maxLocals += declaringClass.getOuterLocalVariablesSlotSize();
	}
	TypeBinding[] parameterTypes;
	if ((parameterTypes = methodBinding.parameters) != null) {
		for (TypeBinding parameterType : parameterTypes) {
			switch (parameterType.id) {
				case TypeIds.T_long :
				case TypeIds.T_double :
					this.maxLocals += 2;
					break;
				default:
					this.maxLocals++;
			}
		}
	}
}

/**
 * Some placed labels might be branching to a goto bytecode which we can optimize better.
 */
public boolean inlineForwardReferencesFromLabelsTargeting(BranchLabel targetLabel, int gotoLocation) {
	if (targetLabel.delegate != null) return false; // already inlined
	int chaining = L_UNKNOWN;
	for (int i = this.countLabels - 1; i >= 0; i--) {
		BranchLabel currentLabel = this.labels[i];
		if (currentLabel.position != gotoLocation) break;
		if (currentLabel == targetLabel) {
			chaining |= L_CANNOT_OPTIMIZE; // recursive
			continue;
		}
		if (currentLabel.isStandardLabel()) {
			if (currentLabel.delegate != null) continue; // ignore since already inlined
			targetLabel.becomeDelegateFor(currentLabel);
			chaining |= L_OPTIMIZABLE; // optimizable, providing no vetoing
			continue;
		}
		// case label
		chaining |= L_CANNOT_OPTIMIZE;
	}
	return (chaining & (L_OPTIMIZABLE|L_CANNOT_OPTIMIZE)) == L_OPTIMIZABLE; // check was some standards, and no case/recursive
}

public void instance_of(TypeBinding typeBinding) {
	this.instance_of(null, typeBinding);
}

public void instance_of(TypeReference typeReference, TypeBinding typeBinding) {
	this.countLabels = 0;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_instanceof;
	writeUnsignedShort(this.constantPool.literalIndexForType(typeBinding));
	this.operandStack.pop(TypeIds.getCategory(typeBinding.id));
	this.operandStack.push(TypeBinding.INT);
}

protected void invoke(byte opcode, int receiverAndArgsSize, int returnTypeSize, char[] declaringClass, char[] selector, char[] signature, TypeBinding type) {
	invoke(opcode, receiverAndArgsSize, returnTypeSize, declaringClass, selector, signature, TypeIds.T_JavaLangObject, type);
}

protected void invoke(byte opcode, int receiverAndArgsSize, int returnTypeSize, char[] declaringClass, char[] selector, char[] signature, int typeId, TypeBinding returnType) {
	invoke18(opcode, receiverAndArgsSize, returnTypeSize, declaringClass, opcode == Opcodes.OPC_invokeinterface, selector, signature, returnType);
}

// Starting with 1.8 we can no longer deduce isInterface from opcode, invokespecial can be used for default methods, too.
// Hence adding explicit parameter 'isInterface', which is needed only for non-ctor invokespecial invocations
// (i.e., other clients may still call the shorter overload).
private void invoke18(byte opcode, int receiverAndArgsSize, int returnTypeSize, char[] declaringClass,
		boolean isInterface, char[] selector, char[] signature, TypeBinding returnType) {
	this.countLabels = 0;
	if (opcode == Opcodes.OPC_invokeinterface) {
		// invokeinterface
		if (this.classFileOffset + 4 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position +=3;
		this.bCodeStream[this.classFileOffset++] = opcode;
		writeUnsignedShort(this.constantPool.literalIndexForMethod(declaringClass, selector, signature, true));
		this.bCodeStream[this.classFileOffset++] = (byte) receiverAndArgsSize;
		this.bCodeStream[this.classFileOffset++] = 0;
	} else {
		// invokespecial
		// invokestatic
		// invokevirtual
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = opcode;
		writeUnsignedShort(this.constantPool.literalIndexForMethod(declaringClass, selector, signature, isInterface));
	}
	this.stackDepth += returnTypeSize - receiverAndArgsSize;
	this.operandStack.pop(receiverAndArgsSize);
	if (returnTypeSize > 0) {
		this.operandStack.push(returnType);
	}
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
}

public void invokeDynamic(int bootStrapIndex, int argsSize, int returnTypeSize, char[] selector, char[] signature,
		TypeBinding type) {
	this.invokeDynamic(bootStrapIndex, argsSize, returnTypeSize, selector, signature, false, null, null, type);
}

public void invokeDynamic(int bootStrapIndex, int argsSize, int returnTypeSize, char[] selector, char[] signature, boolean isConstructorReference, TypeReference lhsTypeReference, TypeReference [] typeArguments,
		TypeBinding type) {
	if (this.classFileOffset + 4 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	int invokeDynamicIndex = this.constantPool.literalIndexForInvokeDynamic(bootStrapIndex, selector, signature);
	this.position +=3;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_invokedynamic;
	writeUnsignedShort(invokeDynamicIndex);
	this.bCodeStream[this.classFileOffset++] = 0;
	this.bCodeStream[this.classFileOffset++] = 0;
	this.stackDepth += returnTypeSize - argsSize;
	this.operandStack.pop(argsSize);
	if (returnTypeSize > 0) {
		this.operandStack.push(type);
	}
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
}

public void invoke(byte opcode, MethodBinding methodBinding, TypeBinding declaringClass) {
	this.invoke(opcode, methodBinding, declaringClass, null);
}

public void invoke(byte opcode, MethodBinding methodBinding, TypeBinding declaringClass, TypeReference[] typeArguments) {
	if (declaringClass == null) declaringClass = methodBinding.declaringClass;
	if ((declaringClass.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
		Util.recordNestedType(this.classFile, declaringClass);
	}
	// compute receiverAndArgsSize
	int receiverAndArgsSize;
	switch(opcode) {
		case Opcodes.OPC_invokestatic :
			receiverAndArgsSize = 0; // no receiver
			break;
		case Opcodes.OPC_invokeinterface :
		case Opcodes.OPC_invokevirtual :
			receiverAndArgsSize = 1; // receiver
			break;
		case Opcodes.OPC_invokespecial :
			receiverAndArgsSize = 1; // receiver
			if (methodBinding.isConstructor()) {
				if (declaringClass.isNestedType()) {
					ReferenceBinding nestedType = (ReferenceBinding) declaringClass;
					// enclosing instances
					receiverAndArgsSize += nestedType.getEnclosingInstancesSlotSize();
//					ReferenceBinding checkedTargetType = nestedType.isAnonymousType() ? (ReferenceBinding)nestedType.superclass().erasure() : nestedType;
//					receiverAndArgsSize += !checkedTargetType.isNestedType() || checkedTargetType.isStatic() ? 0 : checkedTargetType.getEnclosingInstancesSlotSize();
					// outer local variables
					SyntheticArgumentBinding[] syntheticArguments = nestedType.syntheticOuterLocalVariables();
					if (syntheticArguments != null) {
						for (SyntheticArgumentBinding syntheticArgument : syntheticArguments) {
							switch (syntheticArgument.type.id)  {
								case TypeIds.T_double :
								case TypeIds.T_long :
									receiverAndArgsSize += 2;
									break;
								default:
									receiverAndArgsSize++;
									break;
							}
						}
					}
				}
				if (declaringClass.isEnum()) {
					// adding String (name) and int (ordinal)
					receiverAndArgsSize += 2;
				}
			}
			break;
		default :
			return; // should not occur

	}
	for (int i = methodBinding.parameters.length - 1; i >= 0; i--) {
		switch (methodBinding.parameters[i].id) {
			case TypeIds.T_double :
			case TypeIds.T_long :
				receiverAndArgsSize += 2;
				break;
			default :
				receiverAndArgsSize ++;
				break;
		}
	}
	// compute return type size
	int returnTypeSize;
	switch (methodBinding.returnType.id) {
		case TypeIds.T_double :
		case TypeIds.T_long :
			returnTypeSize = 2;
			break;
		case TypeIds.T_void :
			returnTypeSize = 0;
			break;
		default :
			returnTypeSize = 1;
			break;
	}
	invoke18(
			opcode,
			receiverAndArgsSize,
			returnTypeSize,
			declaringClass.constantPoolName(),
			declaringClass.isInterface(),
			methodBinding.selector,
			methodBinding.signature(this.classFile),
			methodBinding.returnType);
}

protected void invokeAccessibleObjectSetAccessible() {
	// invokevirtual: java.lang.reflect.AccessibleObject.setAccessible(Z)V;
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JAVALANGREFLECTACCESSIBLEOBJECT_CONSTANTPOOLNAME,
			ConstantPool.SETACCESSIBLE_NAME,
			ConstantPool.SETACCESSIBLE_SIGNATURE,
			null);
}

protected void invokeArrayNewInstance() {
	// invokestatic: java.lang.reflect.Array.newInstance(Ljava.lang.Class;int[])Ljava.lang.Object;
	invoke(
			Opcodes.OPC_invokestatic,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JAVALANGREFLECTARRAY_CONSTANTPOOLNAME,
			ConstantPool.NewInstance,
			ConstantPool.NewInstanceSignature,
			getPopularBinding(ConstantPool.JavaLangObjectConstantPoolName));
}
public void invokeClassForName() {
	// invokestatic: java.lang.Class.forName(Ljava.lang.String;)Ljava.lang.Class;
	invoke(
		Opcodes.OPC_invokestatic,
		1, // receiverAndArgsSize
		1, // return type size
		ConstantPool.JavaLangClassConstantPoolName,
		ConstantPool.ForName,
		ConstantPool.ForNameSignature,
		getPopularBinding(ConstantPool.JavaLangClassConstantPoolName));
}

protected void invokeClassGetDeclaredConstructor() {
	// invokevirtual: java.lang.Class getDeclaredConstructor([Ljava.lang.Class)Ljava.lang.reflect.Constructor;
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangClassConstantPoolName,
			ConstantPool.GETDECLAREDCONSTRUCTOR_NAME,
			ConstantPool.GETDECLAREDCONSTRUCTOR_SIGNATURE,
			getPopularBinding(ConstantPool.JavaLangReflectConstructorConstantPoolName));
}

protected void invokeClassGetDeclaredField() {
	// invokevirtual: java.lang.Class.getDeclaredField(Ljava.lang.String)Ljava.lang.reflect.Field;
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangClassConstantPoolName,
			ConstantPool.GETDECLAREDFIELD_NAME,
			ConstantPool.GETDECLAREDFIELD_SIGNATURE,
			getPopularBinding(ConstantPool.JAVALANGREFLECTFIELD_CONSTANTPOOLNAME));
}

protected void invokeClassGetDeclaredMethod() {
	// invokevirtual: java.lang.Class getDeclaredMethod(Ljava.lang.String, [Ljava.lang.Class)Ljava.lang.reflect.Method;
	invoke(
			Opcodes.OPC_invokevirtual,
			3, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangClassConstantPoolName,
			ConstantPool.GETDECLAREDMETHOD_NAME,
			ConstantPool.GETDECLAREDMETHOD_SIGNATURE,
			getPopularBinding(ConstantPool.JAVALANGREFLECTMETHOD_CONSTANTPOOLNAME));
}

public void invokeEnumOrdinal(char[] enumTypeConstantPoolName) {
	// invokevirtual: <enumConstantPoolName>.ordinal()
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			enumTypeConstantPoolName,
			ConstantPool.Ordinal,
			ConstantPool.OrdinalSignature,
			TypeIds.T_int,
			TypeBinding.INT);
}

public void invokeIterableIterator(TypeBinding iterableReceiverType) {
	// invokevirtual/interface: <iterableReceiverType>.iterator()
	if ((iterableReceiverType.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
		Util.recordNestedType(this.classFile, iterableReceiverType);
	}
	invoke(
			iterableReceiverType.isInterface() ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // returnTypeSize
			iterableReceiverType.constantPoolName(),
			ConstantPool.ITERATOR_NAME,
			ConstantPool.ITERATOR_SIGNATURE,
			getPopularBinding(ConstantPool.JavaUtilIteratorConstantPoolName));
}

public void invokeAutoCloseableClose(TypeBinding resourceType) {
	// invokevirtual/interface: <resourceType>.close()
	invoke(
			resourceType.erasure().isInterface() ? Opcodes.OPC_invokeinterface : Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			0, // returnTypeSize
			resourceType.constantPoolName(),
			ConstantPool.Close,
			ConstantPool.CloseSignature,
			null);
}

public void invokeThrowableAddSuppressed() {
	invoke(Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			0, // returnTypeSize
			ConstantPool.JavaLangThrowableConstantPoolName,
			ConstantPool.AddSuppressed,
			ConstantPool.AddSuppressedSignature,
			null);
}

public void invokeJavaLangAssertionErrorConstructor(int typeBindingID) {
	// invokespecial: java.lang.AssertionError.<init>(typeBindingID)V
	int receiverAndArgsSize;
	char[] signature;
	switch (typeBindingID) {
		case TypeIds.T_int :
		case TypeIds.T_byte :
		case TypeIds.T_short :
			signature = ConstantPool.IntConstrSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_long :
			signature = ConstantPool.LongConstrSignature;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_float :
			signature = ConstantPool.FloatConstrSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_double :
			signature = ConstantPool.DoubleConstrSignature;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_char :
			signature = ConstantPool.CharConstrSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_boolean :
			signature = ConstantPool.BooleanConstrSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_JavaLangObject :
		case TypeIds.T_JavaLangString :
		case TypeIds.T_null :
			signature = ConstantPool.ObjectConstrSignature;
			receiverAndArgsSize = 2;
			break;
		default:
			return; // should not occur
	}
	invoke(
			Opcodes.OPC_invokespecial,
			receiverAndArgsSize,
			0, // return type size
			ConstantPool.JavaLangAssertionErrorConstantPoolName,
			ConstantPool.Init,
			signature,
			null);
}

public void invokeJavaLangAssertionErrorDefaultConstructor() {
	// invokespecial: java.lang.AssertionError.<init>()V
	invoke(
			Opcodes.OPC_invokespecial,
			1, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangAssertionErrorConstantPoolName,
			ConstantPool.Init,
			ConstantPool.DefaultConstructorSignature,
			null);
}
public void invokeJavaLangIncompatibleClassChangeErrorDefaultConstructor() {
	// invokespecial: java.lang.IncompatibleClassChangeError.<init>()V
	invoke(
			Opcodes.OPC_invokespecial,
			1, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangIncompatibleClassChangeErrorConstantPoolName,
			ConstantPool.Init,
			ConstantPool.DefaultConstructorSignature,
			null);
}
public void invokeJavaLangClassDesiredAssertionStatus() {
	// invokevirtual: java.lang.Class.desiredAssertionStatus()Z;
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangClassConstantPoolName,
			ConstantPool.DesiredAssertionStatus,
			ConstantPool.DesiredAssertionStatusSignature,
			TypeIds.T_boolean,
			TypeBinding.BOOLEAN);
}

public void invokeJavaLangEnumvalueOf(ReferenceBinding binding) {
	// invokestatic: java.lang.Enum.valueOf(Class,String)
	invoke(
			Opcodes.OPC_invokestatic,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangEnumConstantPoolName,
			ConstantPool.ValueOf,
			ConstantPool.ValueOfStringClassSignature,
			getPopularBinding(ConstantPool.JavaLangEnumConstantPoolName));
}

public void invokeJavaLangEnumValues(TypeBinding enumBinding, ArrayBinding arrayBinding) {
	char[] signature = "()".toCharArray(); //$NON-NLS-1$
	signature = CharOperation.concat(signature, arrayBinding.constantPoolName());
	invoke(
			Opcodes.OPC_invokestatic,
			0,  // receiverAndArgsSize
			1,  // return type size
			enumBinding.constantPoolName(),
			TypeConstants.VALUES,
			signature,
			arrayBinding);
}

public void invokeJavaLangErrorConstructor() {
	// invokespecial: java.lang.Error<init>(Ljava.lang.String;)V
	invoke(
			Opcodes.OPC_invokespecial,
			2, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangErrorConstantPoolName,
			ConstantPool.Init,
			ConstantPool.StringConstructorSignature,
			null);
}

public void invokeJavaLangReflectConstructorNewInstance() {
	// invokevirtual: java.lang.reflect.Constructor.newInstance([Ljava.lang.Object;)Ljava.lang.Object;
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangReflectConstructorConstantPoolName,
			ConstantPool.NewInstance,
			ConstantPool.JavaLangReflectConstructorNewInstanceSignature,
			getPopularBinding(ConstantPool.JavaLangObjectSignature));
}

protected void invokeJavaLangReflectFieldGetter(TypeBinding type) {
	char[] selector;
	char[] signature;
	int returnTypeSize;
	int typeID = type.id;
	switch (typeID) {
		case TypeIds.T_int :
			selector = ConstantPool.GET_INT_METHOD_NAME;
			signature = ConstantPool.GET_INT_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		case TypeIds.T_byte :
			selector = ConstantPool.GET_BYTE_METHOD_NAME;
			signature = ConstantPool.GET_BYTE_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		case TypeIds.T_short :
			selector = ConstantPool.GET_SHORT_METHOD_NAME;
			signature = ConstantPool.GET_SHORT_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		case TypeIds.T_long :
			selector = ConstantPool.GET_LONG_METHOD_NAME;
			signature = ConstantPool.GET_LONG_METHOD_SIGNATURE;
			returnTypeSize = 2;
			break;
		case TypeIds.T_float :
			selector = ConstantPool.GET_FLOAT_METHOD_NAME;
			signature = ConstantPool.GET_FLOAT_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		case TypeIds.T_double :
			selector = ConstantPool.GET_DOUBLE_METHOD_NAME;
			signature = ConstantPool.GET_DOUBLE_METHOD_SIGNATURE;
			returnTypeSize = 2;
			break;
		case TypeIds.T_char :
			selector = ConstantPool.GET_CHAR_METHOD_NAME;
			signature = ConstantPool.GET_CHAR_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		case TypeIds.T_boolean :
			selector = ConstantPool.GET_BOOLEAN_METHOD_NAME;
			signature = ConstantPool.GET_BOOLEAN_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
		default :
			selector = ConstantPool.GET_OBJECT_METHOD_NAME;
			signature = ConstantPool.GET_OBJECT_METHOD_SIGNATURE;
			returnTypeSize = 1;
			break;
	}
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			returnTypeSize, // return type size
			ConstantPool.JAVALANGREFLECTFIELD_CONSTANTPOOLNAME,
			selector,
			signature,
			typeID,
			type);
}

protected void invokeJavaLangReflectFieldSetter(TypeBinding type) {
	char[] selector;
	char[] signature;
	int receiverAndArgsSize;
	int typeID = type.id;
	switch (typeID) {
		case TypeIds.T_int :
			selector = ConstantPool.SET_INT_METHOD_NAME;
			signature = ConstantPool.SET_INT_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_byte :
			selector = ConstantPool.SET_BYTE_METHOD_NAME;
			signature = ConstantPool.SET_BYTE_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_short :
			selector = ConstantPool.SET_SHORT_METHOD_NAME;
			signature = ConstantPool.SET_SHORT_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_long :
			selector = ConstantPool.SET_LONG_METHOD_NAME;
			signature = ConstantPool.SET_LONG_METHOD_SIGNATURE;
			receiverAndArgsSize = 4;
			break;
		case TypeIds.T_float :
			selector = ConstantPool.SET_FLOAT_METHOD_NAME;
			signature = ConstantPool.SET_FLOAT_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_double :
			selector = ConstantPool.SET_DOUBLE_METHOD_NAME;
			signature = ConstantPool.SET_DOUBLE_METHOD_SIGNATURE;
			receiverAndArgsSize = 4;
			break;
		case TypeIds.T_char :
			selector = ConstantPool.SET_CHAR_METHOD_NAME;
			signature = ConstantPool.SET_CHAR_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_boolean :
			selector = ConstantPool.SET_BOOLEAN_METHOD_NAME;
			signature = ConstantPool.SET_BOOLEAN_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
		default :
			selector = ConstantPool.SET_OBJECT_METHOD_NAME;
			signature = ConstantPool.SET_OBJECT_METHOD_SIGNATURE;
			receiverAndArgsSize = 3;
			break;
	}
	invoke(
			Opcodes.OPC_invokevirtual,
			receiverAndArgsSize,
			0, // return type size
			ConstantPool.JAVALANGREFLECTFIELD_CONSTANTPOOLNAME,
			selector,
			signature,
			typeID,
			type);
}

public void invokeJavaLangReflectMethodInvoke() {
	// invokevirtual: java.lang.reflect.Method.invoke(Ljava.lang.Object;[Ljava.lang.Object;)Ljava.lang.Object;
	invoke(
			Opcodes.OPC_invokevirtual,
			3, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JAVALANGREFLECTMETHOD_CONSTANTPOOLNAME,
			ConstantPool.INVOKE_METHOD_METHOD_NAME,
			ConstantPool.INVOKE_METHOD_METHOD_SIGNATURE,
			getPopularBinding(ConstantPool.JavaLangObjectSignature));
}

public void invokeJavaUtilIteratorHasNext() {
	// invokeinterface java.util.Iterator.hasNext()Z
	invoke(
			Opcodes.OPC_invokeinterface,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaUtilIteratorConstantPoolName,
			ConstantPool.HasNext,
			ConstantPool.HasNextSignature,
			TypeIds.T_boolean,
			TypeBinding.BOOLEAN);
}

public void invokeJavaUtilIteratorNext() {
	// invokeinterface java.util.Iterator.next()java.lang.Object
	invoke(
			Opcodes.OPC_invokeinterface,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaUtilIteratorConstantPoolName,
			ConstantPool.Next,
			ConstantPool.NextSignature,
			getPopularBinding(ConstantPool.JavaLangObjectSignature));
}

public void invokeJavaUtilObjectsrequireNonNull() {
	// invokestatic: java.util.Objects.requireNonNull(Ljava.lang.Object;)Ljava.lang.Object;
	invoke(
		Opcodes.OPC_invokestatic,
		1, // receiverAndArgsSize
		1, // return type size
		ConstantPool.JavaUtilObjectsConstantPoolName,
		ConstantPool.RequireNonNull,
		ConstantPool.RequireNonNullSignature,
		getPopularBinding(ConstantPool.JavaLangObjectSignature));
}

public void invokeNoClassDefFoundErrorStringConstructor() {
	// invokespecial: java.lang.NoClassDefFoundError.<init>(Ljava.lang.String;)V
	invoke(
			Opcodes.OPC_invokespecial,
			2, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangNoClassDefFoundErrorConstantPoolName,
			ConstantPool.Init,
			ConstantPool.StringConstructorSignature,
			null);
}

public void invokeObjectGetClass() {
	// invokevirtual: java.lang.Object.getClass()Ljava.lang.Class;
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangObjectConstantPoolName,
			ConstantPool.GetClass,
			ConstantPool.GetClassSignature,
			getPopularBinding(ConstantPool.JavaLangClassConstantPoolName));
}

/**
 * The equivalent code performs a string conversion of the TOS
 * @param typeID <CODE>int</CODE>
 */
public void invokeStringConcatenationAppendForType(int typeID) {
	int receiverAndArgsSize;
	char[] declaringClass = null;
	char[] selector = ConstantPool.Append;
	char[] signature = null;
	switch (typeID) {
		case TypeIds.T_int :
		case TypeIds.T_byte :
		case TypeIds.T_short :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendIntSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_long :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendLongSignature;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_float :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendFloatSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_double :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendDoubleSignature;
			receiverAndArgsSize = 3;
			break;
		case TypeIds.T_char :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendCharSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_boolean :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendBooleanSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_JavaLangString :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendStringSignature;
			receiverAndArgsSize = 2;
			break;
		default :
			declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
			signature = ConstantPool.StringBuilderAppendObjectSignature;
			receiverAndArgsSize = 2;
			break;
	}
	TypeBinding type =
		getPopularBinding(ConstantPool.JavaLangStringBuilderConstantPoolName);
	invoke(
			Opcodes.OPC_invokevirtual,
			receiverAndArgsSize,
			1, // return type size
			declaringClass,
			selector,
			signature,
			typeID,
			type);
}

public void invokeStringConcatenationDefaultConstructor() {
	// invokespecial: java.lang.StringBuilder.<init>()V
	char[] declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
	invoke(
			Opcodes.OPC_invokespecial,
			1, // receiverAndArgsSize
			0, // return type size
			declaringClass,
			ConstantPool.Init,
			ConstantPool.DefaultConstructorSignature,
			null);
}

public void invokeStringConcatenationStringConstructor() {
	// invokespecial: java.lang.StringStringBuilder.<init>(java.langString)V
	char[] declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
	invoke(
			Opcodes.OPC_invokespecial,
			2, // receiverAndArgsSize
			0, // return type size
			declaringClass,
			ConstantPool.Init,
			ConstantPool.StringConstructorSignature,
			null);
}

public void invokeStringConcatenationToString() {
	// invokespecial: java.lang.StringBuilder.toString()java.lang.String
	char[] declaringClass = ConstantPool.JavaLangStringBuilderConstantPoolName;
		invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			declaringClass,
			ConstantPool.ToString,
			ConstantPool.ToStringSignature,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}
public void invokeStringEquals() {
	// invokevirtual: java.lang.String.equals()
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangStringConstantPoolName,
			ConstantPool.Equals,
			ConstantPool.EqualsSignature,
			TypeIds.T_boolean,
			TypeBinding.BOOLEAN);
}
public void invokeObjectEquals() {
	// invokevirtual: java.lang.Object.equals()
	invoke(
			Opcodes.OPC_invokevirtual,
			2, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangObjectConstantPoolName,
			ConstantPool.Equals,
			ConstantPool.EqualsSignature,
			TypeIds.T_boolean,
			TypeBinding.BOOLEAN);
}
public void invokeStringHashCode() {
	// invokevirtual: java.lang.String.hashCode()
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangStringConstantPoolName,
			ConstantPool.HashCode,
			ConstantPool.HashCodeSignature,
			TypeIds.T_int,
			TypeBinding.INT);
}
public void invokeStringIntern() {
	// invokevirtual: java.lang.String.intern()
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangStringConstantPoolName,
			ConstantPool.Intern,
			ConstantPool.InternSignature,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}
public void invokeStringValueOf(int typeID) {
	// invokestatic: java.lang.String.valueOf(argumentType)
	char[] signature;
	int receiverAndArgsSize;
	switch (typeID) {
		case TypeIds.T_int :
		case TypeIds.T_byte :
		case TypeIds.T_short :
			signature = ConstantPool.ValueOfIntSignature;
			receiverAndArgsSize = 1;
			break;
		case TypeIds.T_long :
			signature = ConstantPool.ValueOfLongSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_float :
			signature = ConstantPool.ValueOfFloatSignature;
			receiverAndArgsSize = 1;
			break;
		case TypeIds.T_double :
			signature = ConstantPool.ValueOfDoubleSignature;
			receiverAndArgsSize = 2;
			break;
		case TypeIds.T_char :
			signature = ConstantPool.ValueOfCharSignature;
			receiverAndArgsSize = 1;
			break;
		case TypeIds.T_boolean :
			signature = ConstantPool.ValueOfBooleanSignature;
			receiverAndArgsSize = 1;
			break;
		case TypeIds.T_JavaLangObject :
		case TypeIds.T_JavaLangString :
		case TypeIds.T_null :
		case TypeIds.T_undefined :
			signature = ConstantPool.ValueOfObjectSignature;
			receiverAndArgsSize = 1;
			break;
		default :
			return; // should not occur
	}
	invoke(
			Opcodes.OPC_invokestatic,
			receiverAndArgsSize, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangStringConstantPoolName,
			ConstantPool.ValueOf,
			signature,
			typeID,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}

//record PrimitiveConvertorRecord(int t2t, char[] methodName, char[] signature, int typeID, int receiverAndArgsSize, int returnTypeSize) {}
public void invokeExactConversionsSupport(int typeFromTo) {
	// invokestatic: java/lang/runtime/ExactConversionsSupport.is{Functions}(argumentType)
	char[] signature;
	int typeID = typeFromTo & TypeIds.COMPILE_TYPE_MASK;
	int receiverAndArgsSize = TypeIds.getCategory(typeID);
	int returnTypeSize = 1; // boolean always
	char[] methodName;
	switch (typeFromTo) { // TODO: put this in an array of records.
		case TypeIds.Int2Float :
			methodName = ConstantPool.isIntToFloatExact;
			signature = ConstantPool.isIntToFloatExactSignature;
			typeID = TypeIds.T_int;
			break;
		case TypeIds.Long2Float :
			methodName = ConstantPool.isLongToFloatExact;
			signature = ConstantPool.isLongToFloatExactSignature;
			typeID = TypeIds.T_long;
			break;
		case TypeIds.Long2Double :
			methodName = ConstantPool.isLongToDoubleExact;
			signature = ConstantPool.isLongToDoubleExactSignature;
			typeID = TypeIds.T_long;
			break;
		case TypeIds.Float2Double :
			methodName = ConstantPool.isFloatToDoubleExact;
			signature = ConstantPool.isFloatToDoubleExactSignature;
			typeID = TypeIds.T_float;
			break;
		case TypeIds.Double2Byte :
			methodName = ConstantPool.isDoubleToByteExact;
			signature = ConstantPool.isDoubleToByteExactSignature;
			typeID = TypeIds.T_byte;
			break;
		case TypeIds.Double2Short :
			methodName = ConstantPool.isDoubleToShortExact;
			signature = ConstantPool.isDoubleToShortExactSignature;
			typeID = TypeIds.T_short;
			break;
		case TypeIds.Double2Char :
			methodName = ConstantPool.isDoubleToCharExact;
			signature = ConstantPool.isDoubleToCharExactSignature;
			typeID = TypeIds.T_char;
			break;
		case TypeIds.Double2Int :
			methodName = ConstantPool.isDoubleToIntExact;
			signature = ConstantPool.isDoubleToIntExactSignature;
			typeID = TypeIds.T_int;
			break;
		case TypeIds.Double2Long :
			methodName = ConstantPool.isDoubleToLongExact;
			signature = ConstantPool.isDoubleToLongExactSignature;
			typeID = TypeIds.T_long;
			break;
		case TypeIds.Double2Float :
			methodName = ConstantPool.isDoubleToFloatExact;
			signature = ConstantPool.isDoubleToFloatExactSignature;
			typeID = TypeIds.T_float;
			break;
		case TypeIds.Float2Byte :
			methodName = ConstantPool.isFloatToByteExact;
			signature = ConstantPool.isFloatToByteExactSignature;
			typeID = TypeIds.T_byte;
			break;
		case TypeIds.Float2Short :
			methodName = ConstantPool.isFloatToShortExact;
			signature = ConstantPool.isFloatToShortExactSignature;
			typeID = TypeIds.T_short;
			break;
		case TypeIds.Float2Char :
			methodName = ConstantPool.isFloatToCharExact;
			signature = ConstantPool.isFloatToCharExactSignature;
			typeID = TypeIds.T_char;
			break;
		case TypeIds.Float2Int :
			methodName = ConstantPool.isFloatToIntExact;
			signature = ConstantPool.isFloatToIntExactSignature;
			typeID = TypeIds.T_int;
			break;
		case TypeIds.Float2Long :
			methodName = ConstantPool.isFloatToLongExact;
			signature = ConstantPool.isFloatToLongExactSignature;
			typeID = TypeIds.T_long;
			break;
		case TypeIds.Long2Byte :
			methodName = ConstantPool.isLongToByteExact;
			signature = ConstantPool.isLongToByteExactSignature;
			typeID = TypeIds.T_byte;
			break;
		case TypeIds.Long2Short :
			methodName = ConstantPool.isLongToShortExact;
			signature = ConstantPool.isLongToShortExactSignature;
			typeID = TypeIds.T_short;
			break;
		case TypeIds.Long2Char :
			methodName = ConstantPool.isLongToCharExact;
			signature = ConstantPool.isLongToCharExactSignature;
			typeID = TypeIds.T_char;
			break;
		case TypeIds.Long2Int :
			methodName = ConstantPool.isLongToIntExact;
			signature = ConstantPool.isLongToIntExactSignature;
			typeID = TypeIds.T_int;
			break;
		case TypeIds.Int2Byte :
		case TypeIds.Char2Byte :
		case TypeIds.Short2Byte :
			methodName = ConstantPool.isIntToByteExact;
			signature = ConstantPool.isIntToByteExactSignature;
			typeID = TypeIds.T_byte;
			break;
		case TypeIds.Int2Short :
		case TypeIds.Char2Short :
			methodName = ConstantPool.isIntToShortExact;
			signature = ConstantPool.isIntToShortExactSignature;
			typeID = TypeIds.T_short;
			break;
		case TypeIds.Int2Char :
		case TypeIds.Short2Char :
		case TypeIds.Byte2Char:
			methodName = ConstantPool.isIntToCharExact;
			signature = ConstantPool.isIntToCharExactSignature;
			typeID = TypeIds.T_char;
			break;
		default :
			return; // should not occur
	}
	char[] declaringClass = ConstantPool.JAVA_LANG_RUNTIME_EXACTCONVERSIONSSUPPORT;
	invoke(
			Opcodes.OPC_invokestatic,
			receiverAndArgsSize, // receiverAndArgsSize
			returnTypeSize, // return type size
			declaringClass,
			methodName,
			signature,
			typeID,
			TypeBinding.BOOLEAN);
}

public void invokeThrowableToString() {
	char[] declaringClass = ConstantPool.JavaLangThrowableConstantPoolName;
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			declaringClass,
			ConstantPool.ToString,
			ConstantPool.ToStringSignature,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}
public void invokeSystemArraycopy() {
	// invokestatic #21 <Method java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V>
	invoke(
			Opcodes.OPC_invokestatic,
			5, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangSystemConstantPoolName,
			ConstantPool.ArrayCopy,
			ConstantPool.ArrayCopySignature,
			null);
}

public void invokeThrowableGetMessage() {
	// invokevirtual: java.lang.Throwable.getMessage()Ljava.lang.String;
	invoke(
			Opcodes.OPC_invokevirtual,
			1, // receiverAndArgsSize
			1, // return type size
			ConstantPool.JavaLangThrowableConstantPoolName,
			ConstantPool.GetMessage,
			ConstantPool.GetMessageSignature,
			getPopularBinding(ConstantPool.JavaLangStringConstantPoolName));
}

public void ior() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ior;
}

public void irem() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_irem;
}

public void ireturn() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ireturn;
	this.lastAbruptCompletion = this.position;
}

public boolean isDefinitelyAssigned(Scope scope, int initStateIndex, LocalVariableBinding local) {
	// Mirror of UnconditionalFlowInfo.isDefinitelyAssigned(..)
	if ((local.tagBits & TagBits.IsArgument) != 0) {
		return true;
	}
	if (initStateIndex == -1)
		return false;
	int localPosition = local.id + this.maxFieldCount;
	MethodScope methodScope = scope.methodScope();
	// id is zero-based
	if (localPosition < UnconditionalFlowInfo.BitCacheSize) {
		return (methodScope.definiteInits[initStateIndex] & (1L << localPosition)) != 0; // use bits
	}
	// use extra vector
	long[] extraInits = methodScope.extraDefiniteInits[initStateIndex];
	if (extraInits == null)
		return false; // if vector not yet allocated, then not initialized
	int vectorIndex;
	if ((vectorIndex = (localPosition / UnconditionalFlowInfo.BitCacheSize) - 1) >= extraInits.length)
		return false; // if not enough room in vector, then not initialized
	return ((extraInits[vectorIndex]) & (1L << (localPosition % UnconditionalFlowInfo.BitCacheSize))) != 0;
}

public void ishl() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ishl;
}

public void ishr() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ishr;
}

public void istore(int iArg) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.maxLocals <= iArg) {
		this.maxLocals = iArg + 1;
	}
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void istore_0() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.maxLocals == 0) {
		this.maxLocals = 1;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore_0;
}

public void istore_1() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.maxLocals <= 1) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore_1;
}

public void istore_2() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.maxLocals <= 2) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore_2;
}

public void istore_3() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	if (this.maxLocals <= 3) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_istore_3;
}

public void isub() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_isub;
}

public void iushr() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_iushr;
}

public void ixor() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ixor;
}

public void l2d() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_l2d;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.push(TypeBinding.DOUBLE);
}

public void l2f() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_l2f;
}

public void l2i() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.push(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_l2i;
}

public void ladd() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ladd;
}

public void laload() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_laload;
	this.operandStack.xaload();
}

public void land() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_land;
}

public void lastore() {
	this.countLabels = 0;
	this.stackDepth -= 4;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lastore;
}

public void lcmp() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.push(TypeBinding.INT);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lcmp;
}

public void lconst_0() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lconst_0;
}

public void lconst_1() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lconst_1;
}

public void ldc(float constant) {
	this.countLabels = 0;
	int index = this.constantPool.literalIndex(constant);
	this.stackDepth++;
	this.operandStack.push(TypeBinding.FLOAT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (index > 255) {
		// Generate a ldc_w
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc_w;
		writeUnsignedShort(index);
	} else {
		// Generate a ldc
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc;
		this.bCodeStream[this.classFileOffset++] = (byte) index;
	}
}

public void ldc(int constant) {
	this.countLabels = 0;
	int index = this.constantPool.literalIndex(constant);
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (index > 255) {
		// Generate a ldc_w
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc_w;
		writeUnsignedShort(index);
	} else {
		// Generate a ldc
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc;
		this.bCodeStream[this.classFileOffset++] = (byte) index;
	}
}

public void ldc(String constant) {
	this.countLabels = 0;
	int currentCodeStreamPosition = this.position;
	char[] constantChars = constant.toCharArray();
	int index = this.constantPool.literalIndexForLdc(constantChars);
	if (index > 0) {
		// the string already exists inside the constant pool
		// we reuse the same index
		ldcForIndex(index);
	} else {
		// the string is too big to be utf8-encoded in one pass.
		// we have to split it into different pieces.
		// first we clean all side-effects due to the code above
		// this case is very rare, so we can afford to lose time to handle it
		this.position = currentCodeStreamPosition;
		int i = 0;
		int length = 0;
		int constantLength = constant.length();
		byte[] utf8encoding = new byte[Math.min(constantLength + 100, 65535)];
		int utf8encodingLength = 0;
		while ((length < 65532) && (i < constantLength)) {
			char current = constantChars[i];
			// we resize the byte array immediately if necessary
			if (length + 3 > (utf8encodingLength = utf8encoding.length)) {
				System.arraycopy(utf8encoding, 0, utf8encoding = new byte[Math.min(utf8encodingLength + 100, 65535)], 0, length);
			}
			if ((current >= 0x0001) && (current <= 0x007F)) {
				// we only need one byte: ASCII table
				utf8encoding[length++] = (byte) current;
			} else {
				if (current > 0x07FF) {
					// we need 3 bytes
					utf8encoding[length++] = (byte) (0xE0 | ((current >> 12) & 0x0F)); // 0xE0 = 1110 0000
					utf8encoding[length++] = (byte) (0x80 | ((current >> 6) & 0x3F)); // 0x80 = 1000 0000
					utf8encoding[length++] = (byte) (0x80 | (current & 0x3F)); // 0x80 = 1000 0000
				} else {
					// we can be 0 or between 0x0080 and 0x07FF
					// In that case we only need 2 bytes
					utf8encoding[length++] = (byte) (0xC0 | ((current >> 6) & 0x1F)); // 0xC0 = 1100 0000
					utf8encoding[length++] = (byte) (0x80 | (current & 0x3F)); // 0x80 = 1000 0000
				}
			}
			i++;
		}
		// check if all the string is encoded (PR 1PR2DWJ)
		// the string is too big to be encoded in one pass
		newStringContatenation();
		dup();
		// write the first part
		char[] subChars = new char[i];
		System.arraycopy(constantChars, 0, subChars, 0, i);
		System.arraycopy(utf8encoding, 0, utf8encoding = new byte[length], 0, length);
		index = this.constantPool.literalIndex(subChars, utf8encoding);
		ldcForIndex(index);
		// write the remaining part
		invokeStringConcatenationStringConstructor();
		while (i < constantLength) {
			length = 0;
			utf8encoding = new byte[Math.min(constantLength - i + 100, 65535)];
			int startIndex = i;
			while ((length < 65532) && (i < constantLength)) {
				char current = constantChars[i];
				// we resize the byte array immediately if necessary
				if (length + 3 > (utf8encodingLength = utf8encoding.length)) {
					System.arraycopy(utf8encoding, 0, utf8encoding = new byte[Math.min(utf8encodingLength + 100, 65535)], 0, length);
				}
				if ((current >= 0x0001) && (current <= 0x007F)) {
					// we only need one byte: ASCII table
					utf8encoding[length++] = (byte) current;
				} else {
					if (current > 0x07FF) {
						// we need 3 bytes
						utf8encoding[length++] = (byte) (0xE0 | ((current >> 12) & 0x0F)); // 0xE0 = 1110 0000
						utf8encoding[length++] = (byte) (0x80 | ((current >> 6) & 0x3F)); // 0x80 = 1000 0000
						utf8encoding[length++] = (byte) (0x80 | (current & 0x3F)); // 0x80 = 1000 0000
					} else {
						// we can be 0 or between 0x0080 and 0x07FF
						// In that case we only need 2 bytes
						utf8encoding[length++] = (byte) (0xC0 | ((current >> 6) & 0x1F)); // 0xC0 = 1100 0000
						utf8encoding[length++] = (byte) (0x80 | (current & 0x3F)); // 0x80 = 1000 0000
					}
				}
				i++;
			}
			// the next part is done
			int newCharLength = i - startIndex;
			subChars = new char[newCharLength];
			System.arraycopy(constantChars, startIndex, subChars, 0, newCharLength);
			System.arraycopy(utf8encoding, 0, utf8encoding = new byte[length], 0, length);
			index = this.constantPool.literalIndex(subChars, utf8encoding);
			ldcForIndex(index);
			// now on the stack it should be a StringBuffer and a string.
			invokeStringConcatenationAppendForType(TypeIds.T_JavaLangString);
		}
		invokeStringConcatenationToString();
		invokeStringIntern();
	}
}

public void ldc(TypeBinding typeBinding) {
	this.countLabels = 0;
	int index = this.constantPool.literalIndexForType(typeBinding);
	this.stackDepth++;
	this.operandStack.push(getPopularBinding(ConstantPool.JavaLangClassConstantPoolName));
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (index > 255) {
		// Generate a ldc_w
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc_w;
		writeUnsignedShort(index);
	} else {
		// Generate a ldc
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc;
		this.bCodeStream[this.classFileOffset++] = (byte) index;
	}
}

public void ldc2_w(double constant) {
	this.countLabels = 0;
	int index = this.constantPool.literalIndex(constant);
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.DOUBLE);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	// Generate a ldc2_w
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc2_w;
	writeUnsignedShort(index);
}

public void ldc2_w(long constant) {
	this.countLabels = 0;
	int index = this.constantPool.literalIndex(constant);
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	// Generate a ldc2_w
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc2_w;
	writeUnsignedShort(index);
}

public void ldcForIndex(int index) {
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangStringConstantPoolName);
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (index > 255) {
		// Generate a ldc_w
		if (this.classFileOffset + 2 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc_w;
		writeUnsignedShort(index);
	} else {
		// Generate a ldc
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldc;
		this.bCodeStream[this.classFileOffset++] = (byte) index;
	}
}

public void ldiv() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_ldiv;
}

public void lload(int iArg) {
	this.countLabels = 0;
	this.stackDepth += 2;
	if (this.maxLocals <= iArg + 1) {
		this.maxLocals = iArg + 2;
	}
	this.operandStack.push(TypeBinding.LONG);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void lload_0() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.maxLocals < 2) {
		this.maxLocals = 2;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload_0;
}

public void lload_1() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.maxLocals < 3) {
		this.maxLocals = 3;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload_1;
}

public void lload_2() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.maxLocals < 4) {
		this.maxLocals = 4;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload_2;
}

public void lload_3() {
	this.countLabels = 0;
	this.stackDepth += 2;
	this.operandStack.push(TypeBinding.LONG);
	if (this.maxLocals < 5) {
		this.maxLocals = 5;
	}
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lload_3;
}

public void lmul() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lmul;
}

public void lneg() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lneg;
	this.operandStack.peek(TypeBinding.LONG);
}

public final void load(LocalVariableBinding localBinding) {
	load(localBinding.type, localBinding.resolvedPosition);
}

protected final void load(TypeBinding typeBinding, int resolvedPosition) {
	this.countLabels = 0;
	// Using dedicated int bytecode
	switch(typeBinding.id) {
		case TypeIds.T_int :
		case TypeIds.T_byte :
		case TypeIds.T_char :
		case TypeIds.T_boolean :
		case TypeIds.T_short :
			switch (resolvedPosition) {
				case 0 :
					iload_0();
					break;
				case 1 :
					iload_1();
					break;
				case 2 :
					iload_2();
					break;
				case 3 :
					iload_3();
					break;
				//case -1 :
				// internal failure: trying to load variable not supposed to be generated
				//	break;
				default :
					iload(resolvedPosition);
			}
			break;
		case TypeIds.T_float :
			switch (resolvedPosition) {
				case 0 :
					fload_0();
					break;
				case 1 :
					fload_1();
					break;
				case 2 :
					fload_2();
					break;
				case 3 :
					fload_3();
					break;
				default :
					fload(resolvedPosition);
			}
			break;
		case TypeIds.T_long :
			switch (resolvedPosition) {
				case 0 :
					lload_0();
					break;
				case 1 :
					lload_1();
					break;
				case 2 :
					lload_2();
					break;
				case 3 :
					lload_3();
					break;
				default :
					lload(resolvedPosition);
			}
			break;
		case TypeIds.T_double :
			switch (resolvedPosition) {
				case 0 :
					dload_0();
					break;
				case 1 :
					dload_1();
					break;
				case 2 :
					dload_2();
					break;
				case 3 :
					dload_3();
					break;
				default :
					dload(resolvedPosition);
			}
			break;
		default :
			switch (resolvedPosition) {
				case 0 :
					aload_0();
					break;
				case 1 :
					aload_1();
					break;
				case 2 :
					aload_2();
					break;
				case 3 :
					aload_3();
					break;
				default :
					aload(resolvedPosition);
			}
	}
}

public void lookupswitch(CaseLabel defaultLabel, int[] keys, int[] sortedIndexes, CaseLabel[] casesLabel) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	int length = keys.length;
	int pos = this.position;
	defaultLabel.placeInstruction();
	for (int i = 0; i < length; i++) {
		casesLabel[i].placeInstruction();
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lookupswitch;
	for (int i = (3 - (pos & 3)); i > 0; i--) { // faster than % 4
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = 0;
	}
	defaultLabel.branchWide();
	writeSignedWord(length);
	for (int i = 0; i < length; i++) {
		writeSignedWord(keys[sortedIndexes[i]]);
		casesLabel[sortedIndexes[i]].branchWide();
	}
	this.lastAbruptCompletion = this.position; // multiway goto, with no fall through
}

public void lor() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lor;
}

public void lrem() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lrem;
}

public void lreturn() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lreturn;
	this.lastAbruptCompletion = this.position;
}

public void lshl() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lshl;
}

public void lshr() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lshr;
}

public void lstore(int iArg) {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.maxLocals <= iArg + 1) {
		this.maxLocals = iArg + 2;
	}
	if (iArg > 255) { // Widen
		if (this.classFileOffset + 3 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_wide;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore;
		writeUnsignedShort(iArg);
	} else {
		if (this.classFileOffset + 1 >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position += 2;
		this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore;
		this.bCodeStream[this.classFileOffset++] = (byte) iArg;
	}
}

public void lstore_0() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.maxLocals < 2) {
		this.maxLocals = 2;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore_0;
}

public void lstore_1() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.maxLocals < 3) {
		this.maxLocals = 3;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore_1;
}

public void lstore_2() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.maxLocals < 4) {
		this.maxLocals = 4;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore_2;
}

public void lstore_3() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	if (this.maxLocals < 5) {
		this.maxLocals = 5;
	}
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lstore_3;
}

public void lsub() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lsub;
}

public void lushr() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lushr;
}

public void lxor() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop(TypeBinding.LONG);
	this.operandStack.peek(TypeBinding.LONG);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_lxor;
}

public void monitorenter() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_monitorenter;
}

public void monitorexit() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_monitorexit;
}

public void multianewarray(
		TypeReference typeReference,
		TypeBinding typeBinding,
		int dimensions,
		ArrayAllocationExpression allocationExpression) {
	this.countLabels = 0;
	this.stackDepth += (1 - dimensions);
	for (int i = 0; i < dimensions; i++)
		this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(typeBinding);
	if (this.classFileOffset + 3 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position += 2;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_multianewarray;
	writeUnsignedShort(this.constantPool.literalIndexForType(typeBinding));
	this.bCodeStream[this.classFileOffset++] = (byte) dimensions;
}

public void new_(TypeBinding typeBinding) {
	this.new_(null, typeBinding);
}

public void new_(TypeReference typeReference, TypeBinding typeBinding) {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(typeBinding);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 3 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(typeBinding));
}

public void newarray(int arrayTypeCode) {
	this.countLabels = 0;
	if (this.classFileOffset + 1 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position += 2;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_newarray;
	this.bCodeStream[this.classFileOffset++] = (byte) arrayTypeCode;

	ClassScope scope = this.classFile.referenceBinding.scope;
	this.operandStack.pop(TypeBinding.INT);
	this.operandStack.push(switch (arrayTypeCode) {
				case ClassFileConstants.INT_ARRAY -> scope.createArrayType(TypeBinding.INT, 1);
				case ClassFileConstants.BYTE_ARRAY -> scope.createArrayType(TypeBinding.BYTE, 1);
				case ClassFileConstants.BOOLEAN_ARRAY -> scope.createArrayType(TypeBinding.BOOLEAN, 1);
				case ClassFileConstants.SHORT_ARRAY -> scope.createArrayType(TypeBinding.SHORT, 1);
				case ClassFileConstants.CHAR_ARRAY -> scope.createArrayType(TypeBinding.CHAR, 1);
				case ClassFileConstants.LONG_ARRAY -> scope.createArrayType(TypeBinding.LONG, 1);
				case ClassFileConstants.FLOAT_ARRAY -> scope.createArrayType(TypeBinding.FLOAT, 1);
				case ClassFileConstants.DOUBLE_ARRAY -> scope.createArrayType(TypeBinding.DOUBLE, 1);
				default -> throw new UnsupportedOperationException("Unknown base type");	//$NON-NLS-1$
			});
}

public void newArray(ArrayBinding arrayBinding) {
	this.newArray(null, null, arrayBinding);
}

public void newArray(TypeReference typeReference, ArrayAllocationExpression allocationExpression, ArrayBinding arrayBinding) {
	TypeBinding component = arrayBinding.elementsType();
	switch (component.id) {
		case TypeIds.T_int :
			newarray(ClassFileConstants.INT_ARRAY);
			break;
		case TypeIds.T_byte :
			newarray(ClassFileConstants.BYTE_ARRAY);
			break;
		case TypeIds.T_boolean :
			newarray(ClassFileConstants.BOOLEAN_ARRAY);
			break;
		case TypeIds.T_short :
			newarray(ClassFileConstants.SHORT_ARRAY);
			break;
		case TypeIds.T_char :
			newarray(ClassFileConstants.CHAR_ARRAY);
			break;
		case TypeIds.T_long :
			newarray(ClassFileConstants.LONG_ARRAY);
			break;
		case TypeIds.T_float :
			newarray(ClassFileConstants.FLOAT_ARRAY);
			break;
		case TypeIds.T_double :
			newarray(ClassFileConstants.DOUBLE_ARRAY);
			break;
		default :
			anewarray(component);
	}
}

public void newJavaLangAssertionError() {
	// new: java.lang.AssertionError
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangAssertionErrorConstantPoolName);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangAssertionErrorConstantPoolName));
}

public void newJavaLangError() {
	// new: java.lang.Error
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangErrorConstantPoolName);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangErrorConstantPoolName));
}
public void newJavaLangIncompatibleClassChangeError() {
	// new: java.lang.Error
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangIncompatibleClassChangeErrorConstantPoolName);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangIncompatibleClassChangeErrorConstantPoolName));
}
public void newJavaLangMatchException() {
	// new: java.lang.MatchException
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangMatchExceptionConstantPoolName);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangMatchExceptionConstantPoolName));
}
public void invokeJavaLangMatchExceptionConstructor() {
	// invokespecial: java.lang.MatchException.<init>(Ljava/lang/String;Ljava/lang/Throwable;)V
	invoke(
			Opcodes.OPC_invokespecial,
			3, // receiverAndArgsSize
			0, // return type size
			ConstantPool.JavaLangMatchExceptionConstantPoolName,
			ConstantPool.Init,
			ConstantPool.JavaLangMatchExceptionNewInstanceSignature,
			null);
}
public void newNoClassDefFoundError() {
	// new: java.lang.NoClassDefFoundError
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangNoClassDefFoundErrorConstantPoolName);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangNoClassDefFoundErrorConstantPoolName));
}

public void newStringContatenation() {
	// new: java.lang.StringBuilder
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(ConstantPool.JavaLangStringBuilderConstantPoolName);
	if (this.stackDepth > this.stackMax) {
		this.stackMax = this.stackDepth;
	}
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangStringBuilderConstantPoolName));
}

public void newWrapperFor(int typeID) {
	this.countLabels = 0;
	this.stackDepth++;
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset + 2 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_new;
	switch (typeID) {
		case TypeIds.T_int : // new: java.lang.Integer
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangIntegerConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangIntegerConstantPoolName);
		break;
		case TypeIds.T_boolean : // new: java.lang.Boolean
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangBooleanConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangBooleanConstantPoolName);
			break;
		case TypeIds.T_byte : // new: java.lang.Byte
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangByteConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangByteConstantPoolName);
			break;
		case TypeIds.T_char : // new: java.lang.Character
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangCharacterConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangCharacterConstantPoolName);
			break;
		case TypeIds.T_float : // new: java.lang.Float
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangFloatConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangFloatConstantPoolName);
			break;
		case TypeIds.T_double : // new: java.lang.Double
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangDoubleConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangDoubleConstantPoolName);
			break;
		case TypeIds.T_short : // new: java.lang.Short
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangShortConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangShortConstantPoolName);
			break;
		case TypeIds.T_long : // new: java.lang.Long
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangLongConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangLongConstantPoolName);
			break;
		case TypeIds.T_void : // new: java.lang.Void
			writeUnsignedShort(this.constantPool.literalIndexForType(ConstantPool.JavaLangVoidConstantPoolName));
			this.operandStack.push(ConstantPool.JavaLangVoidConstantPoolName);
	}
}

public void nop() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_nop;
}

public void optimizeBranch(int oldPosition, BranchLabel lbl) {
	for (int i = 0; i < this.countLabels; i++) {
		BranchLabel label = this.labels[i];
		if (oldPosition == label.position) {
			label.position = this.position;
			if (label instanceof CaseLabel) {
				int offset = this.position - ((CaseLabel) label).instructionPosition;
				int[] forwardRefs = label.forwardReferences();
				for (int j = 0, length = label.forwardReferenceCount(); j < length; j++) {
					int forwardRef = forwardRefs[j];
					this.writeSignedWord(forwardRef, offset);
				}
			} else {
				int[] forwardRefs = label.forwardReferences();
				for (int j = 0, length = label.forwardReferenceCount(); j < length; j++) {
					final int forwardRef = forwardRefs[j];
					this.writePosition(lbl, forwardRef);
				}
			}
		}
	}
}

public void pop(TypeBinding type) {
	if (TypeIds.getCategory(type.id) == 2)
		pop2();
	else
		pop();
}

public void pop() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(OperandCategory.ONE);
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_pop;
}

public void pop2() {
	this.countLabels = 0;
	this.stackDepth -= 2;
	this.operandStack.pop2();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_pop2;
}

public void pushExceptionOnStack(TypeBinding binding) {
	this.stackDepth = 1;
	this.operandStack.clear();
	this.operandStack.push(binding);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
}

public void pushOnStack(TypeBinding binding) {
	if (++this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	this.operandStack.push(binding);
}

public void record(LocalVariableBinding local) {
	if (this.allLocalsCounter == this.locals.length) {
		// resize the collection
		System.arraycopy(this.locals, 0, this.locals = new LocalVariableBinding[this.allLocalsCounter + LOCALS_INCREMENT], 0, this.allLocalsCounter);
	}
	this.locals[this.allLocalsCounter++] = local;
	local.initializationPCs = new int[4];
	local.initializationCount = 0;
}

public void recordPositionsFrom(int startPC, int sourcePos) {
	this.recordPositionsFrom(startPC, sourcePos, false);
}

public void recordPositionsFrom(int startPC, int sourcePos, boolean widen) {
	/* Record positions in the table, only if nothing has
	 * already been recorded. Since we output them on the way
	 * up (children first for more specific info)
	 * The pcToSourceMap table is always sorted.
	 */
	if ((this.generateAttributes & ClassFileConstants.ATTR_LINES) == 0
			|| sourcePos == 0
			|| (startPC == this.position && !widen)
			|| startPC > this.position)
		return;

	// Widening an existing entry that already has the same source positions
	if (this.pcToSourceMapSize + 4 > this.pcToSourceMap.length) {
		// resize the array pcToSourceMap
		System.arraycopy(this.pcToSourceMap, 0, this.pcToSourceMap = new int[this.pcToSourceMapSize << 1], 0, this.pcToSourceMapSize);
	}
	// lastEntryPC represents the endPC of the lastEntry.
	if (this.pcToSourceMapSize > 0) {
		int lineNumber = -1;
		int previousLineNumber = this.pcToSourceMap[this.pcToSourceMapSize - 1];
		if (this.lineNumberStart == this.lineNumberEnd) {
			// method on one line
			lineNumber = this.lineNumberStart;
		} else {
			// Check next line number if this is the one we are looking for
			int[] lineSeparatorPositions2 = this.lineSeparatorPositions;
			int length = lineSeparatorPositions2.length;
			if (previousLineNumber == 1) {
				if (sourcePos < lineSeparatorPositions2[0]) {
					lineNumber = 1;
				} else if (length == 1 || sourcePos < lineSeparatorPositions2[1]) {
					lineNumber = 2;
				}
			} else if (previousLineNumber < length) {
				if (lineSeparatorPositions2[previousLineNumber - 2] < sourcePos) {
					if (sourcePos < lineSeparatorPositions2[previousLineNumber - 1]) {
						lineNumber = previousLineNumber;
					} else if (sourcePos < lineSeparatorPositions2[previousLineNumber]) {
						lineNumber = previousLineNumber + 1;
					}
				}
			} else if (lineSeparatorPositions2[length - 1] < sourcePos) {
				lineNumber = length + 1;
			}
			if(lineNumber == -1) {
				// since lineSeparatorPositions is zero-based, we pass this.lineNumberStart - 1 and this.lineNumberEnd - 1
				lineNumber = Util.getLineNumber(sourcePos, lineSeparatorPositions2, this.lineNumberStart - 1, this.lineNumberEnd - 1);
			}
		}
		// in this case there is already an entry in the table
		if (previousLineNumber != lineNumber) {
			if (startPC <= this.lastEntryPC) {
				// we forgot to add an entry.
				// search if an existing entry exists for startPC
				int insertionIndex = insertionIndex(this.pcToSourceMap, this.pcToSourceMapSize, startPC);
				if (insertionIndex != -1) {
					// there is no existing entry starting with startPC.
					if (!((insertionIndex > 1) && (this.pcToSourceMap[insertionIndex - 1] == lineNumber))) {
						if(insertionIndex< this.pcToSourceMapSize && this.pcToSourceMap[insertionIndex + 1] == lineNumber) {
							/* the entry at insertionIndex corresponds to an entry with the same line and a PC >= startPC.
							in this case it is relevant to widen this entry instead of creating a new one.
							line1: this(a,
							  b,
							  c);
							with this code we generate each argument. We generate a aload0 to invoke the constructor. There is no entry for this
							aload0 bytecode. The first entry is the one for the argument a.
							But we want the constructor call to start at the aload0 pc and not just at the pc of the first argument.
							So we widen the existing entry
							 */
							this.pcToSourceMap[insertionIndex] = startPC;
						} else {
							// we have to add an entry that won't be sorted. So we sort the pcToSourceMap.
							System.arraycopy(this.pcToSourceMap, insertionIndex, this.pcToSourceMap, insertionIndex + 2, this.pcToSourceMapSize - insertionIndex);
							this.pcToSourceMap[insertionIndex++] = startPC;
							this.pcToSourceMap[insertionIndex] = lineNumber;
							this.pcToSourceMapSize += 2;
						}
					}
				} else if (this.position != this.lastEntryPC) { // no bytecode since last entry pc
					if (this.lastEntryPC == startPC || this.lastEntryPC == this.pcToSourceMap[this.pcToSourceMapSize - 2]) {
						this.pcToSourceMap[this.pcToSourceMapSize - 1] = lineNumber;
					} else {
						this.pcToSourceMap[this.pcToSourceMapSize++] = this.lastEntryPC;
						this.pcToSourceMap[this.pcToSourceMapSize++] = lineNumber;
					}
				} else if (this.pcToSourceMap[this.pcToSourceMapSize - 1] < lineNumber && widen) {
					// see if we can widen the existing entry
					this.pcToSourceMap[this.pcToSourceMapSize - 1] = lineNumber;
				}
			} else {
				// we can safely add the new entry. The endPC of the previous entry is not in conflit with the startPC of the new entry.
				this.pcToSourceMap[this.pcToSourceMapSize++] = startPC;
				this.pcToSourceMap[this.pcToSourceMapSize++] = lineNumber;
			}
		} else {
			/* the last recorded entry is on the same line. But it could be relevant to widen this entry.
			   we want to extend this entry forward in case we generated some bytecode before the last entry that are not related to any statement
			*/
			if (startPC < this.pcToSourceMap[this.pcToSourceMapSize - 2]) {
				int insertionIndex = insertionIndex(this.pcToSourceMap, this.pcToSourceMapSize, startPC);
				if (insertionIndex != -1) {
					// widen the existing entry
					// we have to figure out if we need to move the last entry at another location to keep a sorted table
					/* First we need to check if at the insertion position there is not an existing entry
					 * that includes the one we want to insert. This is the case if pcToSourceMap[insertionIndex - 1] == newLine.
					 * In this case we don't want to change the table. If not, we want to insert a new entry. Prior to insertion
					 * we want to check if it is worth doing an arraycopy. If not we simply update the recorded pc.
					 */
					if (!((insertionIndex > 1) && (this.pcToSourceMap[insertionIndex - 1] == lineNumber))) {
						if (this.pcToSourceMap[insertionIndex + 1] != lineNumber) {
							System.arraycopy(this.pcToSourceMap, insertionIndex, this.pcToSourceMap, insertionIndex + 2, this.pcToSourceMapSize - insertionIndex);
							this.pcToSourceMap[insertionIndex++] = startPC;
							this.pcToSourceMap[insertionIndex] = lineNumber;
							this.pcToSourceMapSize += 2;
						} else {
							this.pcToSourceMap[insertionIndex] = startPC;
						}
					}
				}
			}
		}
		this.lastEntryPC = this.position;
	} else {
		int lineNumber = 0;
		if (this.lineNumberStart == this.lineNumberEnd) {
			// method on one line
			lineNumber = this.lineNumberStart;
		} else {
			// since lineSeparatorPositions is zero-based, we pass this.lineNumberStart - 1 and this.lineNumberEnd - 1
			lineNumber = Util.getLineNumber(sourcePos, this.lineSeparatorPositions, this.lineNumberStart - 1, this.lineNumberEnd - 1);
		}
		// record the first entry
		this.pcToSourceMap[this.pcToSourceMapSize++] = startPC;
		this.pcToSourceMap[this.pcToSourceMapSize++] = lineNumber;
		this.lastEntryPC = this.position;
	}
}
/**
 * @param anExceptionLabel org.eclipse.jdt.internal.compiler.codegen.ExceptionLabel
 */
public void registerExceptionHandler(ExceptionLabel anExceptionLabel) {
	int length;
	if (this.exceptionLabelsCounter == (length = this.exceptionLabels.length)) {
		// resize the exception handlers table
		System.arraycopy(this.exceptionLabels, 0, this.exceptionLabels = new ExceptionLabel[length + LABELS_INCREMENT], 0, length);
	}
	// no need to resize. So just add the new exception label
	this.exceptionLabels[this.exceptionLabelsCounter++] = anExceptionLabel;
}

public void removeNotDefinitelyAssignedVariables(Scope scope, int initStateIndex) {
	// given some flow info, make sure we did not loose some variables initialization
	// if this happens, then we must update their pc entries to reflect it in debug attributes
	for (int i = 0; i < this.visibleLocalsCount; i++) {
		LocalVariableBinding localBinding = this.visibleLocals[i];
		if (localBinding != null && !isDefinitelyAssigned(scope, initStateIndex, localBinding) && localBinding.initializationCount > 0) {
			localBinding.recordInitializationEndPC(this.position);
		}
	}
}

/**
 * Remove all entries in pcToSourceMap table that are beyond this.position
 */
public void removeUnusedPcToSourceMapEntries() {
	if (this.pcToSourceMapSize != 0) {
		while (this.pcToSourceMapSize >= 2 && this.pcToSourceMap[this.pcToSourceMapSize - 2] > this.position) {
			this.pcToSourceMapSize -= 2;
		}
	}
}
public void removeVariable(LocalVariableBinding localBinding) {
	if (localBinding == null) return;
	if (localBinding.initializationCount > 0) {
		localBinding.recordInitializationEndPC(this.position);
	}
	for (int i = this.visibleLocalsCount - 1; i >= 0; i--) {
		LocalVariableBinding visibleLocal = this.visibleLocals[i];
		if (visibleLocal == localBinding){
			this.visibleLocals[i] = null; // this variable is no longer visible afterwards
			return;
		}
	}
}

/**
 * @param referenceMethod org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration
 * @param targetClassFile org.eclipse.jdt.internal.compiler.codegen.ClassFile
 */
public void reset(AbstractMethodDeclaration referenceMethod, ClassFile targetClassFile) {
	init(targetClassFile);
	this.methodDeclaration = referenceMethod;
	this.lambdaExpression = null;

	this.operandStack = createOperandStack(referenceMethod.scope.compilerOptions());

	int[] lineSeparatorPositions2 = this.lineSeparatorPositions;
	if (lineSeparatorPositions2 != null) {
		int length = lineSeparatorPositions2.length;
		int lineSeparatorPositionsEnd = length - 1;
		if (referenceMethod.isClinit()
				|| referenceMethod.isConstructor()) {
			this.lineNumberStart = 1;
			this.lineNumberEnd = length == 0 ? 1 : length;
		} else {
			int start = Util.getLineNumber(referenceMethod.bodyStart, lineSeparatorPositions2, 0, lineSeparatorPositionsEnd);
			this.lineNumberStart = start;
			if (start > lineSeparatorPositionsEnd) {
				this.lineNumberEnd = start;
			} else {
				int end = Util.getLineNumber(referenceMethod.bodyEnd, lineSeparatorPositions2, start - 1, lineSeparatorPositionsEnd);
				if (end >= lineSeparatorPositionsEnd) {
					end = length;
				}
				this.lineNumberEnd = end == 0 ? 1 : end;
			}
		}
	}
	this.preserveUnusedLocals = referenceMethod.scope.compilerOptions().preserveAllLocalVariables;
	initializeMaxLocals(referenceMethod.binding);
}

private OperandStack createOperandStack(CompilerOptions compilerOptions) {
	return JavaFeature.SWITCH_EXPRESSIONS.isSupported(compilerOptions.sourceLevel, compilerOptions.enablePreviewFeatures) ?
										new OperandStack(this.classFile) : new OperandStack.NullStack();
}
public void reset(LambdaExpression lambda, ClassFile targetClassFile) {
	init(targetClassFile);
	this.lambdaExpression = lambda;
	this.methodDeclaration = null;
	this.operandStack = createOperandStack(lambda.scope.compilerOptions());
	int[] lineSeparatorPositions2 = this.lineSeparatorPositions;
	if (lineSeparatorPositions2 != null) {
		int length = lineSeparatorPositions2.length;
		int lineSeparatorPositionsEnd = length - 1;
		int start = Util.getLineNumber(lambda.body().sourceStart, lineSeparatorPositions2, 0, lineSeparatorPositionsEnd);
		this.lineNumberStart = start;
		if (start > lineSeparatorPositionsEnd) {
			this.lineNumberEnd = start;
		} else {
			int end = Util.getLineNumber(lambda.body().sourceEnd, lineSeparatorPositions2, start - 1, lineSeparatorPositionsEnd);
			if (end >= lineSeparatorPositionsEnd) {
				end = length;
			}
			this.lineNumberEnd = end == 0 ? 1 : end;
		}

	}
	this.preserveUnusedLocals = lambda.scope.compilerOptions().preserveAllLocalVariables;
	initializeMaxLocals(lambda.binding);
}

public void reset(SyntheticMethodBinding smb, ClassFile targetClassFile) {
	init(targetClassFile);
	this.lambdaExpression = null;
	this.methodDeclaration = null;
	this.operandStack = new OperandStack.NullStack();
	initializeMaxLocals(smb);
}

public void reset(ClassFile givenClassFile) {
	this.targetLevel = givenClassFile.targetJDK;
	int produceAttributes = givenClassFile.produceAttributes;
	this.generateAttributes = produceAttributes;
	if ((produceAttributes & ClassFileConstants.ATTR_LINES) != 0 && givenClassFile.referenceBinding != null) {
		this.lineSeparatorPositions = givenClassFile.referenceBinding.scope.referenceCompilationUnit().compilationResult.getLineSeparatorPositions();
	} else {
		this.lineSeparatorPositions = null;
	}
}

/**
 * @param targetClassFile The given classfile to reset the code stream
 */
public void resetForProblemClinit(ClassFile targetClassFile) {
	init(targetClassFile);
	this.operandStack = new OperandStack.NullStack();
	initializeMaxLocals(null);
}

public void resetInWideMode() {
	this.wideMode = true;
}
public void resetForCodeGenUnusedLocals() {
	// nothing to do in standard code stream
}
private final void resizeByteArray() {
	int length = this.bCodeStream.length;
	int requiredSize = length + length;
	if (this.classFileOffset >= requiredSize) {
		// must be sure to grow enough
		requiredSize = this.classFileOffset + length;
	}
	System.arraycopy(this.bCodeStream, 0, this.bCodeStream = new byte[requiredSize], 0, length);
}

public void return_() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_return;
	this.lastAbruptCompletion = this.position;
}

public void saload() {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.xaload();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_saload;
}

public void sastore() {
	this.countLabels = 0;
	this.stackDepth -= 3;
	this.operandStack.xastore();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_sastore;
}

/**
 * @param operatorConstant int
 * @param type_ID int
 */
public void sendOperator(int operatorConstant, int type_ID) {
	switch (type_ID) {
		case TypeIds.T_int :
		case TypeIds.T_boolean :
		case TypeIds.T_char :
		case TypeIds.T_byte :
		case TypeIds.T_short :
			switch (operatorConstant) {
				case OperatorIds.PLUS :
					iadd();
					break;
				case OperatorIds.MINUS :
					isub();
					break;
				case OperatorIds.MULTIPLY :
					imul();
					break;
				case OperatorIds.DIVIDE :
					idiv();
					break;
				case OperatorIds.REMAINDER :
					irem();
					break;
				case OperatorIds.LEFT_SHIFT :
					ishl();
					break;
				case OperatorIds.RIGHT_SHIFT :
					ishr();
					break;
				case OperatorIds.UNSIGNED_RIGHT_SHIFT :
					iushr();
					break;
				case OperatorIds.AND :
					iand();
					break;
				case OperatorIds.OR :
					ior();
					break;
				case OperatorIds.XOR :
					ixor();
					break;
			}
			break;
		case TypeIds.T_long :
			switch (operatorConstant) {
				case OperatorIds.PLUS :
					ladd();
					break;
				case OperatorIds.MINUS :
					lsub();
					break;
				case OperatorIds.MULTIPLY :
					lmul();
					break;
				case OperatorIds.DIVIDE :
					ldiv();
					break;
				case OperatorIds.REMAINDER :
					lrem();
					break;
				case OperatorIds.LEFT_SHIFT :
					lshl();
					break;
				case OperatorIds.RIGHT_SHIFT :
					lshr();
					break;
				case OperatorIds.UNSIGNED_RIGHT_SHIFT :
					lushr();
					break;
				case OperatorIds.AND :
					land();
					break;
				case OperatorIds.OR :
					lor();
					break;
				case OperatorIds.XOR :
					lxor();
					break;
			}
			break;
		case TypeIds.T_float :
			switch (operatorConstant) {
				case OperatorIds.PLUS :
					fadd();
					break;
				case OperatorIds.MINUS :
					fsub();
					break;
				case OperatorIds.MULTIPLY :
					fmul();
					break;
				case OperatorIds.DIVIDE :
					fdiv();
					break;
				case OperatorIds.REMAINDER :
					frem();
			}
			break;
		case TypeIds.T_double :
			switch (operatorConstant) {
				case OperatorIds.PLUS :
					dadd();
					break;
				case OperatorIds.MINUS :
					dsub();
					break;
				case OperatorIds.MULTIPLY :
					dmul();
					break;
				case OperatorIds.DIVIDE :
					ddiv();
					break;
				case OperatorIds.REMAINDER :
					drem();
			}
	}
}

public void sipush(int s) {
	this.countLabels = 0;
	this.stackDepth++;
	this.operandStack.push(TypeBinding.INT);
	if (this.stackDepth > this.stackMax)
		this.stackMax = this.stackDepth;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_sipush;
	writeSignedShort(s);
}

public void store(LocalVariableBinding localBinding, boolean valueRequired) {
	int localPosition = localBinding.resolvedPosition;
	// Using dedicated int bytecode
	switch(localBinding.type.id) {
		case TypeIds.T_int :
		case TypeIds.T_char :
		case TypeIds.T_byte :
		case TypeIds.T_short :
		case TypeIds.T_boolean :
			if (valueRequired)
				dup();
			switch (localPosition) {
				case 0 :
					istore_0();
					break;
				case 1 :
					istore_1();
					break;
				case 2 :
					istore_2();
					break;
				case 3 :
					istore_3();
					break;
				//case -1 :
				// internal failure: trying to store into variable not supposed to be generated
				//	break;
				default :
					istore(localPosition);
			}
			break;
		case TypeIds.T_float :
			if (valueRequired)
				dup();
			switch (localPosition) {
				case 0 :
					fstore_0();
					break;
				case 1 :
					fstore_1();
					break;
				case 2 :
					fstore_2();
					break;
				case 3 :
					fstore_3();
					break;
				default :
					fstore(localPosition);
			}
			break;
		case TypeIds.T_double :
			if (valueRequired)
				dup2();
			switch (localPosition) {
				case 0 :
					dstore_0();
					break;
				case 1 :
					dstore_1();
					break;
				case 2 :
					dstore_2();
					break;
				case 3 :
					dstore_3();
					break;
				default :
					dstore(localPosition);
			}
			break;
		case TypeIds.T_long :
			if (valueRequired)
				dup2();
			switch (localPosition) {
				case 0 :
					lstore_0();
					break;
				case 1 :
					lstore_1();
					break;
				case 2 :
					lstore_2();
					break;
				case 3 :
					lstore_3();
					break;
				default :
					lstore(localPosition);
			}
			break;
		default:
			// Reference object
			if (valueRequired)
				dup();
			switch (localPosition) {
				case 0 :
					astore_0();
					break;
				case 1 :
					astore_1();
					break;
				case 2 :
					astore_2();
					break;
				case 3 :
					astore_3();
					break;
				default :
					astore(localPosition);
			}
	}
}

public void swap() {
	this.countLabels = 0;
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_swap;
}

public void tableswitch(CaseLabel defaultLabel, int low, int high, int[] keys, int[] sortedIndexes, CaseLabel[] casesLabel) {
	this.countLabels = 0;
	this.stackDepth--;
	this.operandStack.pop(TypeBinding.INT);
	int length = casesLabel.length;
	int pos = this.position;
	defaultLabel.placeInstruction();
	for (int i = 0; i < length; i++)
		casesLabel[i].placeInstruction();
	if (this.classFileOffset >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position++;
	this.bCodeStream[this.classFileOffset++] = Opcodes.OPC_tableswitch;
	// padding
	for (int i = (3 - (pos & 3)); i > 0; i--) {
		if (this.classFileOffset >= this.bCodeStream.length) {
			resizeByteArray();
		}
		this.position++;
		this.bCodeStream[this.classFileOffset++] = 0;
	}
	defaultLabel.branchWide();
	writeSignedWord(low);
	writeSignedWord(high);
	int i = low, j = 0;
	// the index j is used to know if the index i is one of the missing entries in case of an
	// optimized tableswitch

	while (true) {
		int index = sortedIndexes[j];
		int key = keys[index];
		if (key == i) {
			casesLabel[index].branchWide();
			j++;
			if (i == high) break; // if high is maxint, then avoids wrapping to minint.
		} else {
			defaultLabel.branchWide();
		}
		i++;
	}
	this.lastAbruptCompletion = this.position; // multiway goto, with no fall through
}

public void throwAnyException(LocalVariableBinding anyExceptionVariable) {
	this.load(anyExceptionVariable);
	athrow();
}

@Override
public String toString() {
	StringBuilder buffer = new StringBuilder("( position:"); //$NON-NLS-1$
	buffer.append(this.position);
	buffer.append(",\nstackDepth:"); //$NON-NLS-1$
	buffer.append(this.stackDepth);
	buffer.append(",\nmaxStack:"); //$NON-NLS-1$
	buffer.append(this.stackMax);
	buffer.append(",\nmaxLocals:"); //$NON-NLS-1$
	buffer.append(this.maxLocals);
	buffer.append(")"); //$NON-NLS-1$
	return buffer.toString();
}
protected void writePosition(BranchLabel label) {
	int offset = label.position - this.position + 1;
	if (Math.abs(offset) > 0x7FFF && !this.wideMode) {
		throw new AbortMethod(CodeStream.RESTART_IN_WIDE_MODE, null);
	}
	this.writeSignedShort(offset);
	int[] forwardRefs = label.forwardReferences();
	for (int i = 0, max = label.forwardReferenceCount(); i < max; i++) {
		this.writePosition(label, forwardRefs[i]);
	}
}

protected void writePosition(BranchLabel label, int forwardReference) {
	final int offset = label.position - forwardReference + 1;
	if (Math.abs(offset) > 0x7FFF && !this.wideMode) {
		throw new AbortMethod(CodeStream.RESTART_IN_WIDE_MODE, null);
	}
	if (this.wideMode) {
		if ((label.tagBits & BranchLabel.WIDE) != 0) {
			this.writeSignedWord(forwardReference, offset);
		} else {
			this.writeSignedShort(forwardReference, offset);
		}
	} else {
		this.writeSignedShort(forwardReference, offset);
	}
}

/**
 * Write a signed 16 bits value into the byte array
 * @param value the signed short
 */
private final void writeSignedShort(int value) {
	// we keep the resize in here because it is used outside the code stream
	if (this.classFileOffset + 1 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position += 2;
	this.bCodeStream[this.classFileOffset++] = (byte) (value >> 8);
	this.bCodeStream[this.classFileOffset++] = (byte) value;
}

private final void writeSignedShort(int pos, int value) {
	int currentOffset = this.startingClassFileOffset + pos;
	if (currentOffset + 1 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.bCodeStream[currentOffset] = (byte) (value >> 8);
	this.bCodeStream[currentOffset + 1] = (byte) value;
}

protected final void writeSignedWord(int value) {
	// we keep the resize in here because it is used outside the code stream
	if (this.classFileOffset + 3 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.position += 4;
	this.bCodeStream[this.classFileOffset++] = (byte) ((value & 0xFF000000) >> 24);
	this.bCodeStream[this.classFileOffset++] = (byte) ((value & 0xFF0000) >> 16);
	this.bCodeStream[this.classFileOffset++] = (byte) ((value & 0xFF00) >> 8);
	this.bCodeStream[this.classFileOffset++] = (byte) (value & 0xFF);
}

protected void writeSignedWord(int pos, int value) {
	int currentOffset = this.startingClassFileOffset + pos;
	if (currentOffset + 3 >= this.bCodeStream.length) {
		resizeByteArray();
	}
	this.bCodeStream[currentOffset++] = (byte) ((value & 0xFF000000) >> 24);
	this.bCodeStream[currentOffset++] = (byte) ((value & 0xFF0000) >> 16);
	this.bCodeStream[currentOffset++] = (byte) ((value & 0xFF00) >> 8);
	this.bCodeStream[currentOffset++] = (byte) (value & 0xFF);
}

/**
 * Write a unsigned 16 bits value into the byte array
 * @param value the unsigned short
 */
private final void writeUnsignedShort(int value) {
	// no bound check since used only from within codestream where already checked
	this.position += 2;
	this.bCodeStream[this.classFileOffset++] = (byte) (value >>> 8);
	this.bCodeStream[this.classFileOffset++] = (byte) value;
}

protected void writeWidePosition(BranchLabel label) {
	int labelPos = label.position;
	int offset = labelPos - this.position + 1;
	this.writeSignedWord(offset);
	int[] forwardRefs = label.forwardReferences();
	for (int i = 0, max = label.forwardReferenceCount(); i < max; i++) {
		int forward = forwardRefs[i];
		offset = labelPos - forward + 1;
		this.writeSignedWord(forward, offset);
	}
}

public TypeBinding retrieveLocalType(int currentPC, int resolvedPosition) {

	if (this.operandStack instanceof OperandStack.NullStack)
		return null;

	for (int i = this.allLocalsCounter  - 1 ; i >= 0; i--) {
		LocalVariableBinding localVariable = this.locals[i];
		if (localVariable == null) continue;
		if (resolvedPosition == localVariable.resolvedPosition) {
			inits: for (int j = 0; j < localVariable.initializationCount; j++) {
				int startPC = localVariable.initializationPCs[j << 1];
				int endPC = localVariable.initializationPCs[(j << 1) + 1];
				if (currentPC < startPC) {
					continue inits;
				} else if (endPC == -1) { // still live
					// the current local is an active local
					return localVariable.type;
				} else if (currentPC < endPC) {
					// the current local is an active local
					return localVariable.type;
				}
			}
		}
	}

	if (this.methodDeclaration != null && this.methodDeclaration.binding != null) {
		ReferenceBinding declaringClass = this.methodDeclaration.binding.declaringClass;
		if (resolvedPosition == 0 && !this.methodDeclaration.isStatic()) {
			return declaringClass;
		}
		int enumOffset = declaringClass.isEnum() ? 2 : 0; // String name, int ordinal
		int argSlotSize = 1 + enumOffset; // this==aload0
		if (this.methodDeclaration.binding.isConstructor()) {
			if (declaringClass.isEnum()) {
				switch (resolvedPosition) {
					case 1:
						return this.methodDeclaration.scope.getJavaLangString();
					case 2:
						return TypeBinding.INT;
					default: break;
				}
			}
			if (declaringClass.isNestedType()) {
				ReferenceBinding[] enclosingTypes = declaringClass.syntheticEnclosingInstanceTypes();
				if (enclosingTypes != null) {
					if (resolvedPosition < argSlotSize + enclosingTypes.length)
						return enclosingTypes[resolvedPosition - argSlotSize];
				}
				final SyntheticArgumentBinding[] syntheticOuterLocalVariables = declaringClass.syntheticOuterLocalVariables();
				if (syntheticOuterLocalVariables != null) {
					for (SyntheticArgumentBinding extraSyntheticArgument : syntheticOuterLocalVariables) {
						if (extraSyntheticArgument.resolvedPosition == resolvedPosition)
							return extraSyntheticArgument.type;
					}
				}
			}
		}
	} else if (this.lambdaExpression != null) {
		ReferenceBinding declaringClass = this.lambdaExpression.binding.declaringClass;
		if (resolvedPosition == 0 && !this.lambdaExpression.binding.isStatic()) {
			return declaringClass;
		}
	}

	return null;
}

private TypeBinding getPopularBinding(char[] typeName) {
	Scope scope = this.classFile.referenceBinding.scope;
	Supplier<ReferenceBinding> finder = scope.getCommonReferenceBinding(typeName);
	return finder != null ? finder.get() : TypeBinding.NULL;
}
/**
 * Stack the scope responsible for any pattern access exceptions not trapped by subscopes.
 * Not all scopes bear responsibility: only method scopes (including clinit, init, lambdas)
 * and try block scopes need to trap component pattern accessor exceptions and install
 * handlers that throw a MatchException.
 *
 * @param scope some scope where the buck should stop for all pattern access exceptions underneath
 */
public void pushPatternAccessTrapScope(BlockScope scope) {
	this.accessorExceptionTrapScopes.push(scope);
}

/**
 * Should the reference context associated with the scope handle pattern access exceptions ?
 * @param scope
 * @return true if pattern accesses do occur underneath, false otherwise
 */
public boolean patternAccessorsMayThrow(BlockScope scope) {
	List<ExceptionLabel> patternExceptionLabels = this.patternAccessorMap.get(scope);
	return patternExceptionLabels != null && patternExceptionLabels.size() > 0;
}

public void handleRecordAccessorExceptions(BlockScope scope) {

	this.accessorExceptionTrapScopes.pop();

	List<ExceptionLabel> patternExceptionLabels = this.patternAccessorMap.get(scope);
	if (patternExceptionLabels == null || patternExceptionLabels.isEmpty())
		return;

	pushExceptionOnStack(TypeBinding.wellKnownType(scope, TypeIds.T_JavaLangThrowable));
	patternExceptionLabels.forEach(ExceptionLabel::place);

	newJavaLangMatchException(); // [Throwable, MatchException]
	dup_x1();                    // [MatchException, Throwable, MatchException]
	swap();                      // [MatchException, MatchException, Throwable]
	dup();                       // [MatchException, MatchException, Throwable, Throwable]
	invokeThrowableToString();   // [MatchException, MatchException, Throwable, String]
	swap();                      // [MatchException, MatchException, String, Throwable]
	invokeJavaLangMatchExceptionConstructor(); // [MatchException]
	athrow();
}
void debugStackDepth(int stackDepth1) throws IllegalArgumentException{
	if (stackDepth1 < 0)
		throw new IllegalArgumentException();
}
}
