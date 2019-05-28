/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.mxnet.engine;

import com.amazon.ai.Context;
import com.amazon.ai.Model;
import com.amazon.ai.Translator;
import com.amazon.ai.TranslatorContext;
import com.amazon.ai.inference.Predictor;
import com.amazon.ai.metric.Metric;
import com.amazon.ai.metric.Metrics;
import com.amazon.ai.ndarray.NDArray;
import com.amazon.ai.ndarray.NDFactory;
import com.amazon.ai.ndarray.NDList;
import com.amazon.ai.ndarray.types.DataDesc;

public class MxPredictor<I, O> implements Predictor<I, O> {

    MxModel model;
    private Translator<I, O> transformer;
    Context context;
    private Module module;
    private DataDesc[] dataDesc;
    NDFactory factory;
    Metrics metrics;
    private long timestamp;

    MxPredictor(MxModel model, Translator<I, O> transformer, Context context) {
        this.factory = MxNDFactory.SYSTEM_FACTORY.newSubFactory(context);
        this.model = model;
        this.transformer = transformer;
        this.context = context;
        Module.Builder builder = new Module.Builder(context, model, false);
        module = builder.build();
        metrics = new Metrics();
    }

    @Override
    public O predict(I input) {
        timestamp = System.nanoTime();

        try (PredictorContext inputCtx = new PredictorContext();
                PredictorContext outputCtx = new PredictorContext()) {
            NDList ndList = transformer.processInput(inputCtx, input);
            preprocessEnd();

            NDList result = forward(ndList);
            forwardEnd(result);

            return transformer.processOutput(outputCtx, result);
        } finally {
            postProcessEnd();
        }
    }

    @Override
    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    private NDList forward(NDList ndList) {
        rebindIfNeeded(ndList);

        return module.forward(ndList);
    }

    private void rebindIfNeeded(NDList ndList) {
        if (dataDesc == null) {
            dataDesc = new DataDesc[ndList.size()];
        } else {
            if (dataDesc.length != ndList.size()) {
                throw new IllegalArgumentException(
                        "Unexpected input size: "
                                + dataDesc.length
                                + ", expected: "
                                + ndList.size());
            }

            for (int i = 0; i < dataDesc.length; ++i) {
                DataDesc actuall = ndList.get(i).getDataDescriptor();
                if (!actuall.getShape().equals(dataDesc[i].getShape())) {
                    // TODO: rebind module
                    return;
                }
            }
        }

        for (int i = 0; i < dataDesc.length; ++i) {
            NDArray array = ndList.get(i);
            dataDesc[i] = array.getDataDescriptor();
        }
    }

    private void preprocessEnd() {
        if (metrics != null) {
            long tmp = System.nanoTime();
            long duration = tmp - timestamp;
            timestamp = tmp;
            metrics.addMetric(new Metric("Preprocess", duration, "nano"));
        }
    }

    private void forwardEnd(NDList list) {
        if (metrics != null) {
            // JnaUtils.waitAll();
            list.waitToRead();
            long tmp = System.nanoTime();
            long duration = tmp - timestamp;
            timestamp = tmp;
            metrics.addMetric(new Metric("Inference", duration, "nano"));
        }
    }

    private void postProcessEnd() {
        if (metrics != null) {
            long tmp = System.nanoTime();
            long duration = tmp - timestamp;
            timestamp = tmp;
            metrics.addMetric(new Metric("Postprocess", duration, "nano"));
        }
    }

    @Override
    public void close() {
        module.close();
        factory.close();
    }

    private class PredictorContext implements TranslatorContext {

        private NDFactory ctxFactory;

        public PredictorContext() {
            ctxFactory = factory.newSubFactory();
        }

        @Override
        public Model getModel() {
            return model;
        }

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public NDFactory getNDFactory() {
            return ctxFactory;
        }

        @Override
        public Metrics getMetrics() {
            return metrics;
        }

        @Override
        public void close() {
            ctxFactory.close();
        }
    }
}
