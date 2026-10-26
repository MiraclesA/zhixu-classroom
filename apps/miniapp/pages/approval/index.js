const {api,error}=require('../../lib/api');
Page({data:{pending:[],history:[],notifications:[],opinions:{},name:'',busy:false,image:''},onLoad(){this.setData({name:getApp().globalData.user?.name||''});this.refresh();},
 async refresh(){if(getApp().globalData.user?.role==='counselor'){error(Error('辅导员统计请使用 Web 端'));return;}try{const [pending,history,notifications]=await Promise.all([api('/approvals/pending'),api('/leave/applications/mine'),api('/notifications')]);this.setData({pending,history,notifications});}catch(e){error(e);}},
 opinion(e){this.setData({[`opinions.${e.currentTarget.dataset.id}`]:e.detail.value});},
 decide(e){if(this.data.busy)return;const {id,decision}=e.currentTarget.dataset;wx.showModal({title:decision==='APPROVED'?'通过申请':'驳回申请',content:'该结果将由服务器记录，并可能进入下一审批节点。',success:async r=>{if(!r.confirm)return;this.setData({busy:true});try{await api(`/approvals/${id}/decision`,'POST',{decision,reason:this.data.opinions[id]||''});wx.showToast({title:'审批已保存'});await this.refresh();}catch(errorValue){error(errorValue);}finally{this.setData({busy:false});}}});},
 image(e){wx.downloadFile({url:getApp().globalData.baseURL+'/attachments/'+e.currentTarget.dataset.id,header:{Authorization:'Bearer '+getApp().globalData.token},success:r=>{if(r.statusCode===200)wx.previewImage({urls:[r.tempFilePath]});else error(Error('无权读取图片'));},fail:()=>error(Error('图片读取失败'))});},
 logout(){api('/logout','POST',{}).catch(()=>{});getApp().globalData.token='';wx.reLaunch({url:'/pages/login/index'});}
});
