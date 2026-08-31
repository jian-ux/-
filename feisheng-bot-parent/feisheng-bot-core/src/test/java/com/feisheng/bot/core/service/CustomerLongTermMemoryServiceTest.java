package com.feisheng.bot.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feisheng.bot.core.entity.BotCustomer;
import com.feisheng.bot.core.entity.BotCustomerMemory;
import com.feisheng.bot.core.mapper.BotCustomerMapper;
import com.feisheng.bot.core.mapper.BotCustomerMemoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerLongTermMemoryServiceTest {
    @Mock private BotCustomerMapper customerMapper;
    @Mock private BotCustomerMemoryMapper memoryMapper;

    @Test
    void acceptsOnlyExplicitStableFactsAndRendersASeparateMemorySection() {
        BotCustomer customer = new BotCustomer();
        customer.setId(7L);
        when(customerMapper.selectOne(any())).thenReturn(customer);
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        CustomerLongTermMemoryService service = new CustomerLongTermMemoryService(
            customerMapper, memoryMapper, new ObjectMapper());

        CustomerLongTermMemoryService.Snapshot snapshot = service.updateFromCustomerMessage(
            "dingtalk", "user-1", "我们公司是星河科技，我负责合同审批，手机号 13800138000。");

        assertEquals("星河科技", snapshot.memories().get("company").value());
        assertEquals("合同审批", snapshot.memories().get("role").value());
        assertFalse(service.contextFor("今天天气怎么样？", snapshot).isPresent());
        String context = service.contextFor("这个合同怎么操作？", snapshot).orElseThrow();
        assertTrue(context.contains("客户长期记忆"));
        assertTrue(context.contains("星河科技"));
        assertFalse(context.contains("13800138000"));

        ArgumentCaptor<BotCustomerMemory> captor = ArgumentCaptor.forClass(BotCustomerMemory.class);
        verify(memoryMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(memory ->
            "user_explicit".equals(memory.getSource()) && "ACTIVE".equals(memory.getStatus())));
    }

    @Test
    void ignoresNegatedFactsAndPlaygroundCustomers() {
        CustomerLongTermMemoryService service = new CustomerLongTermMemoryService(
            customerMapper, memoryMapper, new ObjectMapper());

        CustomerLongTermMemoryService.Snapshot negated = service.updateFromCustomerMessage(
            "dingtalk", "user-2", "我不是管理员，也不使用企业版。");
        CustomerLongTermMemoryService.Snapshot playground = service.updateFromCustomerMessage(
            "playground", "trial", "我们是星河科技，我是管理员。");

        assertTrue(negated.memories().isEmpty());
        assertTrue(playground.memories().isEmpty());
        verify(customerMapper, never()).insert(any(BotCustomer.class));
        verify(customerMapper, never()).updateById(any(BotCustomer.class));
        verify(memoryMapper, never()).insert(any(BotCustomerMemory.class));
    }

    @Test
    void loadsStoredSummaryAndMemoriesInStableOrder() {
        BotCustomer customer = new BotCustomer();
        customer.setId(8L);
        customer.setLongTermSummary("客户已确认使用点签电子合同");
        when(customerMapper.selectOne(any())).thenReturn(customer);

        BotCustomerMemory first = memory("role", "管理员", 2L);
        BotCustomerMemory second = memory("company", "星河科技", 1L);
        when(memoryMapper.selectList(any())).thenReturn(List.of(first, second));

        CustomerLongTermMemoryService service = new CustomerLongTermMemoryService(
            customerMapper, memoryMapper, new ObjectMapper());

        CustomerLongTermMemoryService.Snapshot snapshot = service.load("web", "user-3");

        assertEquals("客户已确认使用点签电子合同", snapshot.summary());
        assertEquals("星河科技", snapshot.memories().get("company").value());
        assertEquals(List.of("company", "role"), snapshot.memories().keySet().stream().toList());
    }

    private BotCustomerMemory memory(String key, String value, long id) {
        BotCustomerMemory memory = new BotCustomerMemory();
        memory.setId(id);
        memory.setMemoryKey(key);
        memory.setMemoryValue(value);
        memory.setSource("user_explicit");
        memory.setStatus("ACTIVE");
        memory.setConfidence(0.95D);
        memory.setUpdatedAt(new Date());
        return memory;
    }
}
