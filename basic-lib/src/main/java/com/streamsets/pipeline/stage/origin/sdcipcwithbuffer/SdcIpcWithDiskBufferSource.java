/**
 * Copyright 2016 StreamSets Inc.
 * <p>
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.sdcipcwithbuffer;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.api.ext.ContextExtensions;
import com.streamsets.pipeline.api.ext.RecordReader;
import com.streamsets.pipeline.lib.fragmentqueue.FileFragmentQueue;
import com.streamsets.pipeline.lib.fragmentqueue.FragmentQueue;
import com.streamsets.pipeline.lib.fragmentqueue.MemoryBufferFragmentQueue;
import com.streamsets.pipeline.lib.http.HttpConfigs;
import com.streamsets.pipeline.lib.http.HttpReceiver;
import com.streamsets.pipeline.lib.httpsource.AbstractHttpServerSource;
import com.streamsets.pipeline.lib.sdcipc.SdcIpcRequestFragmenter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

public class SdcIpcWithDiskBufferSource extends AbstractHttpServerSource<HttpReceiver> {

  public static final String IPC_PATH = "/ipc/v1";

  private final FragmentQueue queue;
  private final long waitTimeForEmptyBatches;
  private ContextExtensions contextExtensions;

  public SdcIpcWithDiskBufferSource(
      HttpConfigs httpConfigs,
      int maxFragmentsInMemory,
      int maxDiskSpaceMB,
      long waitTimeForEmptyBatches
  ) {
    super(httpConfigs, new HttpReceiver(IPC_PATH,
        httpConfigs,
        new SdcIpcRequestFragmenter(),
        new MemoryBufferFragmentQueue(maxFragmentsInMemory, new FileFragmentQueue(maxDiskSpaceMB))
    ));
    queue = (FragmentQueue) getReceiver().getWriter();
    this.waitTimeForEmptyBatches = waitTimeForEmptyBatches;
  }

  @Override
  protected List<ConfigIssue> init() {
    contextExtensions = (ContextExtensions) getContext();
    List<ConfigIssue> issues = getReceiver().init(getContext());
    issues.addAll(getReceiver().getWriter().init(getContext()));
    if (issues.isEmpty()) {
      issues.addAll(super.init());
    }
    return issues;
  }

  @Override
  public void destroy() {
    super.destroy();
    getReceiver().getWriter().destroy();
    getReceiver().destroy();
  }

  @Override
  protected void produceSleep() {
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    List<byte[]> fragments = null;
    try {
      fragments = queue.poll(maxBatchSize, waitTimeForEmptyBatches);
    } catch (InterruptedException ex) {
      //NOP
    } catch (IOException ex) {
      throw new StageException(Errors.IPC_ORIG_W_BUFFER_00, ex.toString(), ex);
    }
    try {
      if (fragments != null) {
        for (byte[] fragment : fragments) {
          RecordReader recordReader = contextExtensions.createRecordReader(new ByteArrayInputStream(fragment),
              0,
              getReceiver().getWriter().getMaxFragmentSizeKB() * 1000
          );
          Record record = recordReader.readRecord();
          while (record != null) {
            batchMaker.addRecord(record);
            record = recordReader.readRecord();
          }
        }
      }
    } catch (IOException ex) {
      throw new OnRecordErrorException(Errors.IPC_ORIG_W_BUFFER_01, ex.toString(), ex);
    }
    return super.produce(lastSourceOffset, maxBatchSize, batchMaker);
  }
}
