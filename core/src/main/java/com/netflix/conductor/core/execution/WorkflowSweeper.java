/**
 * Copyright 2016 Netflix, Inc.
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
/**
 * 
 */
package com.netflix.conductor.core.execution;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.execution.ApplicationException.Code;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;

/**
 * @author Viren
 * @author Vikram
 *
 */
@Singleton
public class WorkflowSweeper {

	private static Logger logger = LoggerFactory.getLogger(WorkflowSweeper.class);

	private ExecutorService es;
	
	private Configuration config;
	
	private QueueDAO queues;
	
	private int executorThreadPoolSize;
	
	private static final String className = WorkflowSweeper.class.getSimpleName();
		
	@Inject
	public WorkflowSweeper(WorkflowExecutor executor, Configuration config, QueueDAO queues) {
		this.config = config;
		this.queues = queues;
		this.executorThreadPoolSize = config.getIntProperty("workflow.sweeper.thread.count", 5);
		if(this.executorThreadPoolSize > 0) {
			this.es = Executors.newFixedThreadPool(executorThreadPoolSize);
			init(executor);
			logger.info("Workflow Sweeper Initialized");
		} else {
			logger.warn("Workflow sweeper is DISABLED");
		}
		
	}

	public void init(WorkflowExecutor executor) {
		
		ScheduledExecutorService deciderPool = Executors.newScheduledThreadPool(1);
		
		deciderPool.scheduleWithFixedDelay(() -> {

			try{
				boolean disable = config.disableSweep();
				if (disable) {
					logger.info("Workflow sweep is disabled.");
					return;
				}
				List<String> workflowIds = queues.pop(WorkflowExecutor.deciderQueue, 2 * executorThreadPoolSize, 2000);
				sweep(workflowIds, executor);
				
			}catch(Exception e){
				Monitors.error(className, "sweep");
				logger.error(e.getMessage(), e);
				
			}
			
			
		}, 500, 500, TimeUnit.MILLISECONDS);
	}

	public void sweep(List<String> workflowIds, WorkflowExecutor executor) throws Exception {
		
		List<Future<?>> futures = new LinkedList<>();
		for (String workflowId : workflowIds) {
			Future<?> future = es.submit(() -> {
				try {
					
					WorkflowContext ctx = new WorkflowContext(config.getAppId());
					WorkflowContext.set(ctx);
					if(logger.isDebugEnabled()) {
						logger.debug("Running sweeper for workflow {}", workflowId);
					}
					boolean done = executor.decide(workflowId);
					if(!done) {
						queues.setUnackTimeout(WorkflowExecutor.deciderQueue, workflowId, config.getSweepFrequency() * 1000);
					} else {
						queues.remove(WorkflowExecutor.deciderQueue, workflowId);
					}
					
				} catch (ApplicationException e) {
					if(e.getCode().equals(Code.NOT_FOUND)) {
						logger.error("Workflow NOT found for id: " + workflowId, e);
						queues.remove(WorkflowExecutor.deciderQueue, workflowId);
					}
					
				} catch (Exception e) {
					Monitors.error(className, "sweep");
					logger.error("Error running sweep for " + workflowId, e);
				}
			});
			futures.add(future);
		}

		for (Future<?> future : futures) {
			future.get();
		}
	
	}
	
}
