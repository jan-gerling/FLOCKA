from locust import HttpLocust, TaskSet, task, TaskSequence, seq_task
import requests, re, random

USER_PORT = '8080'
STOCK_PORT = '8081'
ORDER_PORT = '8082'
PAYMENT_PORT = '8083'
def client_request(locust_user):


# Users Service
def create_user(locust_user):
    response = locust_user.client.post(":%s/users/create" %(USER_PORT))
    try:
        locust_user.user_id = re.search('\((.+?)\)', response.text).group(1)
    except AttributeError:
        locust_user.interrupt() 
    return 

def remove_user(locust_user):
    response = locust_user.client.delete(":%s/users/remove/%s" %(USER_PORT, locust_user.user_id))
    return response.text

def find_user(locust_user):
    response = locust_user.client.get(":%s/users/find/%s" %(USER_PORT, locust_user.user_id))
    return response.text

def get_credit(locust_user):
    response = locust_user.client.get(":%s/users/credit/%s" %(USER_PORT, locust_user.user_id))
    try:
        credit = re.search(',(.+?)\)', response.text).group(1)
        return credit
    except AttributeError:
        return "unknown"

def subtract_credit(locust_user, credit):
    response = locust_user.client.post(":%s/users/credit/subtract/%s/%i" %(USER_PORT, locust_user.user_id, credit))
    if response.text[:16] == 'CreditSubtracted':
        operation_performed = re.search(',true,', response.text)
        if operation_performed is not None:
            locust_user.credit -= credit
    return response.text

def add_credit(locust_user, credit):
    response = locust_user.client.post(":%s/users/credit/add/%s/%i" %(USER_PORT, locust_user.user_id, credit), catch_response=True)
    if response.text[:11] == 'CreditAdded':
        operation_performed = re.search(',true,', response.text)
        if operation_performed is not None:
            locust_user.credit += credit
    else:
        response.failure('')
        print("error adding credit: "+response.text)
    return response.text

# Order Service
def create_order(locust_user):
    response = locust_user.client.post(":%s/orders/create/%s" %(ORDER_PORT, locust_user.user_id), catch_response=True)
    try:
        order_id = re.search('\((.+?),', response.text).group(1)
        if response.text[:12] == 'OrderCreated':
            locust_user.my_orders.append(order_id)
            return order_id
    except AttributeError:
        response.failure('')
        print("error creating order: "+response.text)
    

def remove_order(locust_user, order_id):
    response = locust_user.client.delete(":%s/orders/remove/%s" %(ORDER_PORT, order_id))
    if response.text[:12] == 'OrderDeleted':
        try:
            locust_user.my_orders.remove(order_id)
        except ValueError:
            print('removed an order that was not mine')
    return response.text

def find_order(locust_user, order_id):
    response = locust_user.client.get(":%s/orders/find/%s" %(ORDER_PORT, order_id))
    return response.text

def order_add_item(locust_user, order_id, item_id):
    #response = locust_user.client.post(":%s/orders/addItem/%s/%s" %(ORDER_PORT, order_id, item_id))
    response = locust_user.client.post(":%s/orders/item/add/%s/%s" %(ORDER_PORT, order_id, item_id))

def order_remove_item(locust_user, order_id, item_id):
    #response = locust_user.client.post(":%s/orders/removeItem/%s/%s" %(ORDER_PORT, order_id, item_id))
    response = locust_user.client.post(":%s/orders/item/remove/%s/%s" %(ORDER_PORT, order_id, item_id))

def checkout_order(locust_user, order_id):
    response = locust_user.client.post(":%s/orders/checkout/%s" %(ORDER_PORT, order_id))
    if response.status_code == 200:
        try:
            locust_user.my_orders.remove(order_id)
            #we also have to substract credit
        except ValueError:
            print('removed an order that was not mine')
    return response.text

# Stock Service
def stock_availability(locust_user, item_id):
    return locust_user.client.get(":%s/stock/availability/%s" %(STOCK_PORT, item_id))
    
def add_stock(locust_user, item_id, quantity):
    return locust_user.client.post(":%s/stock/add/%s/%s" %(STOCK_PORT, item_id, quantity))

def subtract_stock(locust_user, item_id, quantity):
    return locust_user.client.post(":%s/stock/subtract/%s/%s" %(STOCK_PORT, item_id, quantity))

def create_item(locust_user):
    response = locust_user.client.post(":%s/stock/item/create/" %(STOCK_PORT), catch_response=True)
    try:
        return re.search('\((.+?)\)', response.text).group(1)
    except AttributeError:
        response.failure('No item created')
        return None

# aux functions
def populate_items(locust_user):
    items_created = []
    for i in range(random.randint(5, 10)):
        new_item = create_item(locust_user)
        if new_item is not None:
            add_stock(locust_user, new_item, 1000)
            items_created.append(new_item)
    return items_created


class UserBehavior(TaskSet):
    def on_start(self):
        """ on_start is called when a Locust start before any task is scheduled """
        create_user(self)
        self.credit = 0
        self.my_orders = []
        self.items_available = populate_items(self)
        add_credit(self, random.randint(1000000, 5000000))

    def on_stop(self):
        print('my credit: {}, credit in db: {}'.format(self.credit, get_credit(self)))

    @task
    def add(self):
        credit = random.randint(1, 10)
        add_credit(self, credit)

    @task
    def sub(self):
        credit = random.randint(1, 10)
        subtract_credit(self, credit)
    
    @task
    class make_order(TaskSequence):
        @seq_task(1)
        def create_new_order(self):
            self.actual_order = create_order(self.parent)
            if self.actual_order is None:
                self.interrupt() 
                
        @seq_task(2)
        @task(random.randint(1, 5))
        def populate_order(self):
            item = random.choice(self.parent.items_available)
            order_add_item(self.parent, self.actual_order, item)
        '''
        @seq_task(3)
        def checkout(self):
            checkout_order(self.parent, self.actual_order)
        '''
class WebsiteUser(HttpLocust):
    task_set = UserBehavior
    min_wait = 500
    max_wait = 900