本项目是一个 OpenAI API 兼容的服务端程序

多个APP使用类似的API，尽管域名不同，其中一个

  <a href="https://apps.apple.com/us/app/ai-keyboard-smart-writing/id6446992925">App Store</a> 

    
#### 支持的模型id

gpt-4.1✅

gpt-5.4-mini✅

gpt--5-nano✅

claude-haiku-4-5✅

deepseek-flash✅

grok-4.20-0309-non-reasoning✅

支持的功能

	/v1/chat/completions
	/v1/messages

function calling支持有限

测试示例

 	curl -X POST 'http://127.0.0.1:89/v1/chat/completions' \
 	--header 'Content-Type: application/json' \
 	--data '{"stream":false,"messages":[{"role":"user","content":"hello"}],"model":"gpt-5.4-mini"}'

支持deepseek-flash\grok-4.20-0309-non-reasoning当model id以deepseek\grok开头时自动走响应逻辑

其他model id会被上游自动替换gpt-5-nano，o3-mini返回gpt-5.4-mini

~~更多API联系📧patches.camera_0m@icloud.com~~
