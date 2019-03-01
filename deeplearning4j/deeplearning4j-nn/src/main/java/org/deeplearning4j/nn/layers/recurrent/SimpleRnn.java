/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.nn.layers.recurrent;

import lombok.val;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.SimpleRnnParamInitializer;
import org.deeplearning4j.nn.workspace.ArrayType;
import org.deeplearning4j.nn.workspace.LayerWorkspaceMgr;
import org.nd4j.base.Preconditions;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastCopyOp;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastMulOp;
import org.nd4j.linalg.api.ops.impl.transforms.custom.LayerNorm;
import org.nd4j.linalg.api.ops.impl.transforms.custom.LayerNormBp;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.linalg.primitives.Triple;

import static org.nd4j.linalg.indexing.NDArrayIndex.all;
import static org.nd4j.linalg.indexing.NDArrayIndex.point;

/**
 * Simple RNN - aka "vanilla" RNN is the simplest type of recurrent neural network layer.
 * It implements out_t = activationFn( in_t * inWeight + out_(t-1) * recurrentWeights + bias).
 *
 * Note that other architectures (LSTM, etc) are usually more effective, especially for longer time series
 *
 * @author Alex Black
 */
public class SimpleRnn extends BaseRecurrentLayer<org.deeplearning4j.nn.conf.layers.recurrent.SimpleRnn> {
    public static final String STATE_KEY_PREV_ACTIVATION = "prevAct";

    public SimpleRnn(NeuralNetConfiguration conf) {
        super(conf);
    }

    @Override
    public INDArray rnnTimeStep(INDArray input, LayerWorkspaceMgr workspaceMgr) {
        setInput(input, workspaceMgr);
        INDArray last = stateMap.get(STATE_KEY_PREV_ACTIVATION);
        INDArray out = activateHelper(last, false, false, workspaceMgr).getFirst();
        try(MemoryWorkspace ws = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()){
            stateMap.put(STATE_KEY_PREV_ACTIVATION, out.get(all(), all(), point(out.size(2)-1)).dup());
        }
        return out;
    }

    @Override
    public INDArray rnnActivateUsingStoredState(INDArray input, boolean training, boolean storeLastForTBPTT, LayerWorkspaceMgr workspaceMgr) {
        setInput(input, workspaceMgr);
        INDArray last = tBpttStateMap.get(STATE_KEY_PREV_ACTIVATION);
        INDArray out = activateHelper(last, training, false, workspaceMgr).getFirst();
        if(storeLastForTBPTT){
            try(MemoryWorkspace ws = Nd4j.getWorkspaceManager().scopeOutOfWorkspaces()){
                tBpttStateMap.put(STATE_KEY_PREV_ACTIVATION, out.get(all(), all(), point(out.size(2)-1)));
            }
        }
        return out;
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon, LayerWorkspaceMgr workspaceMgr) {
        return tbpttBackpropGradient(epsilon, -1, workspaceMgr);
    }

    @Override
    public Pair<Gradient, INDArray> tbpttBackpropGradient(INDArray epsilon, int tbpttBackLength, LayerWorkspaceMgr workspaceMgr) {
        assertInputSet(true);
        if(epsilon.ordering() != 'f' || !Shape.hasDefaultStridesForShape(epsilon))
            epsilon = epsilon.dup('f');

        //First: Do forward pass to get gate activations and Zs
        Triple<INDArray,INDArray, INDArray> p = activateHelper(null, true, true, workspaceMgr);

        INDArray w = getParamWithNoise(SimpleRnnParamInitializer.WEIGHT_KEY, true, workspaceMgr);
        INDArray rw = getParamWithNoise(SimpleRnnParamInitializer.RECURRENT_WEIGHT_KEY, true, workspaceMgr);
        INDArray b = getParamWithNoise(SimpleRnnParamInitializer.BIAS_KEY, true, workspaceMgr);
        INDArray g = (hasLayerNorm() ? getParamWithNoise(SimpleRnnParamInitializer.GAIN_KEY, true, workspaceMgr) : null);

        INDArray wg = gradientViews.get(SimpleRnnParamInitializer.WEIGHT_KEY);
        INDArray rwg = gradientViews.get(SimpleRnnParamInitializer.RECURRENT_WEIGHT_KEY);
        INDArray bg = gradientViews.get(SimpleRnnParamInitializer.BIAS_KEY);
        INDArray gg = (hasLayerNorm() ? gradientViews.get(SimpleRnnParamInitializer.GAIN_KEY) : null);

        gradientsFlattened.assign(0);

        IActivation a = layerConf().getActivationFn();

        val tsLength = input.size(2);

        INDArray epsOut = workspaceMgr.createUninitialized(ArrayType.ACTIVATION_GRAD, input.shape(), 'f');

        INDArray dldzNext = null;
        long end;
        if(tbpttBackLength > 0){
            end = Math.max(0, tsLength-tbpttBackLength);
        } else {
            end = 0;
        }
        for( long i = tsLength-1; i>= end; i--){
            INDArray dldaCurrent = epsilon.get(all(), all(), point(i));
            INDArray aCurrent = p.getFirst().get(all(), all(), point(i));
            INDArray zCurrent = p.getSecond().get(all(), all(), point(i));
            INDArray nCurrent = (hasLayerNorm() ? p.getThird().get(all(), all(), point(i)) : null);
            INDArray inCurrent = input.get(all(), all(), point(i));
            INDArray epsOutCurrent = epsOut.get(all(), all(), point(i));

            if(dldzNext != null){
                //Backprop the component of dL/da (for current time step) from the recurrent connections
                Nd4j.gemm(dldzNext, rw, dldaCurrent, false, true, 1.0, 1.0);
            }
            INDArray dldzCurrent = a.backprop(zCurrent.dup(), dldaCurrent.dup()).getFirst();

            //Handle masking
            INDArray maskCol = null;
            if( maskArray != null){
                //Mask array: shape [minibatch, tsLength]
                //If mask array is present (for example, with bidirectional RNN) -> need to zero out these errors to
                // avoid using errors from a masked time step to calculate the parameter gradients
                maskCol = maskArray.getColumn(i);
                dldzCurrent.muliColumnVector(maskCol);
            }

            INDArray dldnCurrent;
            if(hasLayerNorm()) {
                dldnCurrent = workspaceMgr.createUninitialized(ArrayType.BP_WORKING_MEM, dldzCurrent.dataType(), dldzCurrent.shape());
                INDArray ggCur = workspaceMgr.createUninitialized(ArrayType.BP_WORKING_MEM, gg.dataType(), gg.shape());
                INDArray bgCur = workspaceMgr.createUninitialized(ArrayType.BP_WORKING_MEM, bg.dataType(), bg.shape());
                Nd4j.getExecutioner().exec(new LayerNormBp(nCurrent, g, b, dldzCurrent, dldnCurrent, ggCur, bgCur, 1));
                gg.addi(ggCur);
                bg.addi(bgCur);
            }else{
                dldnCurrent = dldzCurrent;
                //Bias gradients
                bg.addi(dldzCurrent.sum(0));
            }

            //weight gradients:
            Nd4j.gemm(inCurrent, dldnCurrent, wg, true, false, 1.0, 1.0);

            //Recurrent weight gradients:
            if(dldzNext != null) {
                Nd4j.gemm(aCurrent, dldzNext, rwg, true, false, 1.0, 1.0);
            }

            //Epsilon out to layer below (i.e., dL/dIn)
            Nd4j.gemm(dldnCurrent, w, epsOutCurrent, false, true, 1.0, 0.0);

            dldzNext = dldzCurrent;

            if( maskArray != null){
                //If mask array is present: Also need to zero out errors to avoid sending anything but 0s to layer below for masked steps
                epsOutCurrent.muliColumnVector(maskCol);
            }
        }

        weightNoiseParams.clear();

        Gradient grad = new DefaultGradient(gradientsFlattened);
        grad.gradientForVariable().put(SimpleRnnParamInitializer.WEIGHT_KEY, wg);
        grad.gradientForVariable().put(SimpleRnnParamInitializer.RECURRENT_WEIGHT_KEY, rwg);
        grad.gradientForVariable().put(SimpleRnnParamInitializer.BIAS_KEY, bg);
        if(hasLayerNorm()){
            grad.gradientForVariable().put(SimpleRnnParamInitializer.GAIN_KEY, gg);
        }

        epsOut = backpropDropOutIfPresent(epsOut);
        return new Pair<>(grad, epsOut);
    }

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public INDArray activate(boolean training, LayerWorkspaceMgr workspaceMgr){
        return activateHelper(null, training, false, workspaceMgr).getFirst();
    }

    private Triple<INDArray,INDArray,INDArray> activateHelper(INDArray prevStepOut, boolean training, boolean forBackprop, LayerWorkspaceMgr workspaceMgr){
        assertInputSet(false);
        Preconditions.checkState(input.rank() == 3,
                "3D input expected to RNN layer expected, got " + input.rank());

        applyDropOutIfNecessary(training, workspaceMgr);
        val m = input.size(0);
        val tsLength = input.size(2);
        val nOut = layerConf().getNOut();

        INDArray w = getParamWithNoise(SimpleRnnParamInitializer.WEIGHT_KEY, training, workspaceMgr);
        INDArray rw = getParamWithNoise(SimpleRnnParamInitializer.RECURRENT_WEIGHT_KEY, training, workspaceMgr);
        INDArray b = getParamWithNoise(SimpleRnnParamInitializer.BIAS_KEY, training, workspaceMgr);
        INDArray g = (hasLayerNorm() ? getParamWithNoise(SimpleRnnParamInitializer.GAIN_KEY, training, workspaceMgr) : null);

        INDArray out = workspaceMgr.createUninitialized(ArrayType.ACTIVATIONS, new long[]{m, nOut, tsLength}, 'f');
        INDArray outZ = (forBackprop ? workspaceMgr.createUninitialized(ArrayType.BP_WORKING_MEM, out.shape()) : null);
        INDArray outPreNorm = (forBackprop && hasLayerNorm() ? workspaceMgr.createUninitialized(ArrayType.BP_WORKING_MEM, out.shape(), 'f') : null);

        if(input.ordering() != 'f' || Shape.strideDescendingCAscendingF(input))
            input = workspaceMgr.dup(ArrayType.ACTIVATIONS, input, 'f');

        //TODO implement 'mmul across time' optimization

        if(!hasLayerNorm()) {
            //Minor performance optimization: do the "add bias" first:
            Nd4j.getExecutioner().exec(new BroadcastCopyOp(out, b, out, 1));
        }

        IActivation a = layerConf().getActivationFn();

        for( int i=0; i<tsLength; i++ ){
            //out = activationFn(in*w + last*rw + bias)
            INDArray currOut = out.get(all(), all(), point(i)); //F order
            INDArray currIn = input.get(all(), all(), point(i));
            if(hasLayerNorm()){
                INDArray currOutPreNorm = (forBackprop ? outPreNorm : out).get(all(), all(), point(i));
                Nd4j.gemm(currIn, w, currOutPreNorm, false, false, 1.0, 0.0);
                Nd4j.getExecutioner().exec(new LayerNorm(currOutPreNorm, g, b, currOut, 1));
            }else{
                Nd4j.gemm(currIn, w, currOut, false, false, 1.0, 1.0);  //beta = 1.0 to keep previous contents (bias)
            }

            if(i > 0 || prevStepOut != null){
                Nd4j.gemm(prevStepOut, rw, currOut, false, false, 1.0, 1.0);    //beta = 1.0 to keep previous contents
            }

            if(forBackprop){
                outZ.get(all(), all(), point(i)).assign(currOut);
            }

            a.getActivation(currOut, training);

            prevStepOut = currOut;
        }

        //Apply mask, if present:
        if(maskArray != null){
            //Mask should be shape [minibatch, tsLength]
            Nd4j.getExecutioner().exec(new BroadcastMulOp(out, maskArray, out, 0, 2));
            if(forBackprop){
                Nd4j.getExecutioner().exec(new BroadcastMulOp(outZ, maskArray, outZ, 0, 2));
            }
        }

        return new Triple<>(out, outZ, outPreNorm);
    }

    @Override
    public boolean hasLayerNorm(){
        return layerConf().hasLayerNorm();
    }
}
